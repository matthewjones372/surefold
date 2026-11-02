package dev.surefold.actor.projection

import dev.surefold.Clock
import dev.surefold.actor.Behaviour
import dev.surefold.actor.EventCodec
import dev.surefold.actor.FeedEvent
import dev.surefold.actor.JournalFeed
import dev.surefold.actor.OffsetStore
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Signal
import dev.surefold.actor.SlicedFeed
import dev.surefold.actor.Slices
import dev.surefold.actor.behaviour
import dev.surefold.actor.isDeliveryMark
import dev.surefold.actor.onSignal
import dev.surefold.actor.onStart
import dev.surefold.actor.stay
import dev.surefold.clock
import dev.surefold.stream.Failing
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.blocking
import dev.surefold.stream.map
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.runFold
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * One event a projection follows: whose it is, its sequence number there, its [value], and its [offset] in the feed.
 * The offset is saved by `runProjecting` once the element reaches the end of the stream. [metadata] is what the
 * append that wrote it carried (spec 0123), for a publisher to send on: a trace's ids, and log annotations.
 */
class Followed<out A : Any> internal constructor(
    val id: PersistenceId,
    val sequence: Long,
    val value: A,
    val offset: Long,
    internal val progress: Progress,
    val metadata: Map<String, String> = emptyMap(),
) {
    internal fun <B : Any> carrying(value: B) = Followed(id, sequence, value, offset, progress, metadata)
}

/** Which read model an element belongs to, and where its offset goes. */
internal class Progress(val name: String, val offsets: OffsetStore)

/** Where a projection starts: [follow]. */
object Projection {

    /**
     * Every event of [kind] from [feed], decoded by [codec], from the one after the offset [offsets] holds for [name]
     * or from the first, each as [EventCodec.decodeAll] reads it, so one upgraded to several is followed as several.
     * Once it has caught up it asks the feed again every [every], on surefold's clock, and it never
     * ends by itself: a run's `stop()` wakes it. Each event is handled at least once; end it with `runProjecting`. The
     * marks persistent entities write of reliable deliveries (spec 0079) are skipped.
     */
    fun <E : Any> follow(
        feed: JournalFeed,
        kind: String,
        codec: EventCodec<E>,
        offsets: OffsetStore,
        name: String,
        every: Duration = 1.seconds,
        batch: Int = 256,
    ): Stream<Nothing, Followed<E>> = following(codec, offsets, name, every, batch) { offset, limit ->
        feed.after(kind, offset, limit)
    }

    /**
     * Partition [partition] of [partitions] of a read model (spec 0106): as [follow], but only the events of ids whose
     * slices are in [slices] of it, and with its own offset, saved under `name#partition`. Together the partitions
     * follow every event of [kind] once, each id's in order.
     */
    fun <E : Any> partitioned(
        feed: SlicedFeed,
        kind: String,
        codec: EventCodec<E>,
        offsets: OffsetStore,
        name: String,
        partition: Int,
        partitions: Int,
        every: Duration = 1.seconds,
        batch: Int = 256,
    ): Stream<Nothing, Followed<E>> {
        val slices = slices(partition, partitions)
        return following(codec, offsets, partitionName(name, partition), every, batch) { offset, limit ->
            feed.after(kind, slices, offset, limit)
        }
    }

    /** The slices partition [partition] of [partitions] follows: an even share of them, in order. */
    fun slices(partition: Int, partitions: Int): IntRange = Slices.partition(partition, partitions)

    /** What partition [partition] of read model [name] saves its offset under. */
    fun partitionName(name: String, partition: Int): String = "$name#$partition"

    /**
     * An actor that runs what [start] starts for as long as it lives, and closes it when it stops: how a partition
     * runs where the cluster places it (spec 0106). It takes no messages.
     */
    fun worker(start: () -> AutoCloseable): Behaviour<Unit, Unit, Nothing> {
        // Each call is one actor's: what it started, closed when it stops, and started afresh if it restarts.
        var running: AutoCloseable? = null
        return behaviour<Unit, Unit>(Unit) { _, _, _ -> stay() }
            .onStart { running = start() }
            .onSignal { _, _, signal ->
                if (signal == Signal.Stopping) running?.close().also { running = null }
                stay()
            }
    }

    private fun <E : Any> following(
        codec: EventCodec<E>,
        offsets: OffsetStore,
        name: String,
        every: Duration,
        batch: Int,
        after: (offset: Long, limit: Int) -> List<FeedEvent>,
    ): Stream<Nothing, Followed<E>> {
        require(batch > 0) { "a batch of $batch reads nothing" }
        val time = clock.get()
        val progress = Progress(name, offsets)
        return Stream.blocking(
            open = { Cursor(after, offsets.load(name) ?: 0, every, batch, time) to ArrayDeque<Followed<E>>() },
            next = { (cursor, upgraded) ->
                if (upgraded.isEmpty()) {
                    val event = cursor.next()
                    val values = codec.decodeAll(event.bytes)
                    // An event upgraded to several (spec 0091) is read again from its first if a run stops among
                    // them: each but the last carries the offset before the event's, and only the last carries its own.
                    values.forEachIndexed { at, value ->
                        val offset = if (at == values.lastIndex) event.offset else event.offset - 1
                        upgraded += Followed(event.id, event.sequence, value, offset, progress, event.metadata)
                    }
                }
                upgraded.removeFirst()
            },
            wake = { (cursor, _) -> cursor.wake() },
            close = { },
        )
    }
}

/** One run's place in the feed: what it has read and not yet handed on, and the thread that reads it. */
private class Cursor(
    private val after: (offset: Long, limit: Int) -> List<FeedEvent>,
    private var last: Long,
    private val every: Duration,
    private val batch: Int,
    private val time: Clock,
) {
    private val read = ArrayDeque<FeedEvent>()

    @Volatile
    private var reader: Thread? = null

    /** The next event, waiting [every] at a time until there is one; a [wake] ends the wait by interrupting it. */
    fun next(): FeedEvent {
        reader = Thread.currentThread()
        while (read.isEmpty()) {
            val more = after(last, batch)
            if (more.isEmpty()) {
                time.sleep(every)
            } else {
                last = more.last().offset
                read += more.filterNot { isDeliveryMark(it.bytes) }
            }
        }
        return read.removeFirst()
    }

    fun wake() {
        reader?.interrupt()
    }
}

/** [f] on each event, keeping its offset. */
fun <E, A : Any, B : Any> Stream<E, Followed<A>>.mapFollowed(f: (Followed<A>) -> B): Stream<E, Followed<B>> =
    map { event -> event.carrying(f(event)) }

/** [f] on each event, which may `fail` the run, keeping its offset. */
@JvmName("mapFollowedOrFailDeclaring")
fun <F, A : Any, B : Any> Stream<Nothing, Followed<A>>.mapFollowedOrFail(
    f: Failing<F>.(Followed<A>) -> B,
): Stream<F, Followed<B>> = mapOrFail<F, Followed<A>, Followed<B>> { event -> event.carrying(f(this, event)) }

/** As above, for a stream whose failure is already named. */
fun <E, A : Any, B : Any> Stream<E, Followed<A>>.mapFollowedOrFail(
    f: Failing<E>.(Followed<A>) -> B,
): Stream<E, Followed<B>> = mapOrFail<E, Followed<A>, Followed<B>> { event -> event.carrying(f(this, event)) }

/**
 * A run that saves each event's offset once its element reaches the end of the stream, so a projection started
 * again goes on after the last one saved. It answers how many elements reached the end.
 */
fun <E> Stream<E, Followed<*>>.runProjecting(): Run<E, Long> =
    map { event ->
        event.progress.offsets.save(event.progress.name, event.offset)
        1L
    }.runFold(0L, Long::plus)
