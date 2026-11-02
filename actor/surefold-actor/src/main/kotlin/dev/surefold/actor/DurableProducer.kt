package dev.surefold.actor

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Schedule
import dev.surefold.Scope
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.increment
import dev.surefold.logInfo
import dev.surefold.logWarn
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A producer whose unconfirmed commands are kept in this scope's journal (spec 0085), so a crash loses none of them:
 * started again under the same [id], on this node or another, it sends each entity's first unconfirmed command again
 * and numbers on from where it stopped. Its deliveries carry [id] alone, so an entity's deduplication holds across
 * the restart. [codec] keeps each command with its delivery blanked, and a command is sent again through
 * [Delivered.redeliver]. Two producers running under one [id] at once conflict, as two writers for one entity do.
 * It snapshots every 1,000 events and prunes what a snapshot covers, where the scope's stores allow. A journal that
 * throws restarts its actor, 100 ms after the first throw and doubling to 5 s, and the restart replays what was
 * written; until a replay succeeds, `send` answers [Unwritten] at once (spec 0098).
 */
fun <F, M : Any> Scope<F>.durableProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    drainWithin: Duration = 30.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = meters(id)
    val recovered = CountDownLatch(1)
    val down = AtomicReference<Throwable?>()
    val actor = spawn(
        "producer-$id",
        outbox(id, codec, resendAfter, room, keep, meters, recovered, down, route),
        restart = backoff(),
    )
    return Producer(room, keep, within, meters, Encoding(id, codec, actor), recovered, down)
        .drainedOnClose(this, id, drainWithin)
}

/** A durable producer among these test actors, as [Scope.durableProducer]; its resends wait on [TestActors.advance]. */
fun <M : Any> TestActors.durableProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = ProducerMeters(Gauge { }, Counter { }, Counter { }, Counter { })
    val recovered = CountDownLatch(1)
    val down = AtomicReference<Throwable?>()
    val actor = spawn(
        "producer-$id",
        outbox(id, codec, resendAfter, room, keep, meters, recovered, down, route),
        restart = backoff(),
    )
    return Producer(room, keep, within, meters, Encoding(id, codec, actor), recovered, down)
}

/**
 * A durable producer's commands encoded on the caller's thread, so a command its codec refuses, or one that cannot be
 * rebuilt with a new delivery, throws to the caller and is never kept. The delivery it is built with is blank: the
 * actor numbers it, and each send rebuilds it through [Delivered.redeliver].
 */
private class Encoding<M : Any>(
    private val producer: String,
    private val codec: EventCodec<M>,
    private val actor: ActorRef<Any>,
) : Keeping<M> {
    override fun keep(to: String, command: (Delivery) -> M): Handed? {
        val blank = Delivery(producer, to, 0, Delivery.NoOne)
        val bytes = codec.encode(command(blank))
        val kept = codec.decode(bytes)
        require(kept is Delivered) { "a durable producer sends only Delivered commands, not $kept" }
        kept.redeliver(blank)
        return KeepDurably(to, bytes).takeIf { actor.handed(it) }
    }
}

/**
 * The durable producer [id] started again as a child of this actor, to send only what an earlier run of it kept
 * (spec 0099), and that child, which stopping stops it. It holds room for all it recovers, so its `drain` waits for
 * every command; it takes no new ones. It restarts and answers [Unwritten] as [Scope.durableProducer] does.
 */
fun <M : Any> Ctx<*>.resumedProducer(
    id: String,
    codec: EventCodec<M>,
    resendAfter: Duration,
    route: (to: String) -> ActorRef<M>,
): Pair<Producer<M>, ActorRef<*>> {
    val room = room(Int.MAX_VALUE)
    val meters = ProducerMeters(Gauge { }, Counter { }, Counter { }, Counter { })
    val recovered = CountDownLatch(1)
    val down = AtomicReference<Throwable?>()
    val actor = spawn(
        "producer-$id",
        outbox(id, codec, resendAfter, room, Int.MAX_VALUE, meters, recovered, down, route),
        restart = backoff(),
    )
    val producer = Producer(room, Int.MAX_VALUE, Duration.ZERO, meters, Encoding(id, codec, actor), recovered, down)
    return producer to actor
}

/** A durable producer's restarts: short enough that a blip costs little, long enough not to hammer a dead journal. */
private fun <E> backoff(): Schedule<Failure<E>, Duration> =
    Schedule.exponential<Failure<E>>(100.milliseconds).delayed { _, delay -> minOf(delay, 5.seconds) }

/** What a durable producer writes: a command kept, as bytes, and a command confirmed. */
private sealed interface OutboxEvent {
    data class Kept(val to: String, val sequence: Long, val bytes: ByteArray) : OutboxEvent

    data class Done(val to: String, val sequence: Long) : OutboxEvent
}

/**
 * A durable producer's state: its outboxes, each command as the codec wrote it, and the number each entity's next
 * command takes, which outlives the commands confirmed before it.
 */
private data class Stored(val outboxes: Outboxes<ByteArray> = Outboxes(), val next: Map<String, Long> = emptyMap())

private const val KEPT = 1
private const val DONE = 2
private const val SNAPSHOT_EVERY = 1_000

/** Sent at once after a start: claim room for what it recovered, and send it. */
private data object Recovered

/** The durable producer's actor: [producing]'s protocol, with each command kept and confirmed in the journal first. */
private fun <M : Any> outbox(
    producer: String,
    codec: EventCodec<M>,
    resendAfter: Duration,
    room: Semaphore,
    keep: Int,
    meters: ProducerMeters,
    recovered: CountDownLatch,
    down: AtomicReference<Throwable?>,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Remembered<Stored>, JournalConflict> {
    // It outlives a restart, whose replay finds the commands that already hold room.
    val held = HeldRoom(room, keep, meters)

    val sending = Sending(producer, codec, meters, route)
    return persistent<Any, OutboxEvent, Stored>(
        id = PersistenceId("surefold-producer", producer),
        empty = Stored(),
        codec = OutboxEventCodec,
        snapshots = every(SNAPSHOT_EVERY, OutboxesCodec, prune = Prune.always),
        command = { ctx, stored, message ->
            when (message) {
                is KeepDurably -> {
                    val sequence = stored.next[message.to] ?: 1
                    val (_, send) = stored.outboxes.kept(message.to, sequence, message.bytes)
                    persist(OutboxEvent.Kept(message.to, sequence, message.bytes)).then { _ ->
                        // Written: from here a throw leaves its room held, for the restart's replay to find it, and
                        // the caller hears so before the send, which cannot take it back.
                        held.kept(message.to, sequence)
                        message.written()
                        send?.let { sending.perform(ctx, it) }
                    }
                }

                is Confirmed -> when (val confirmed = stored.outboxes.confirmed(message.to, message.sequence)) {
                    null -> none()

                    else -> persist(OutboxEvent.Done(message.to, message.sequence)).then { after ->
                        held.confirmed(after.outboxes, message.to, message.sequence)
                        confirmed.second?.let { sending.perform(ctx, it) }
                    }
                }

                ResendUnconfirmed -> none().then { now -> sending.resend(ctx, now.outboxes) }

                Recovered -> none().then { now ->
                    held.claim(now.outboxes)
                    recovered.countDown()
                    sending.resend(ctx, now.outboxes)
                }

                else -> unhandled()
            }
        },
        event = ::applied,
    ).onThrow { message, cause -> threw(producer, down, held, message, cause) }.onStart { ctx ->
        if (down.getAndSet(null) != null) logInfo("producer $producer writes to its journal again")
        ctx.timers.every(ResendUnconfirmed, resendAfter, ResendUnconfirmed)
        // What the start recovered holds room, and goes out at once rather than a resendAfter later.
        ctx.timers.after(Recovered, Duration.ZERO, Recovered)
    }
}

/** How a durable producer sends what [Outboxes] says to: decoded from its bytes, and redelivered from it. */
private class Sending<M : Any>(
    private val producer: String,
    private val codec: EventCodec<M>,
    private val meters: ProducerMeters,
    private val route: (String) -> ActorRef<M>,
) {
    fun perform(ctx: Ctx<Any>, send: Send<ByteArray>) {
        val (to, sequence, bytes) = send
        val kept = codec.decode(bytes)
        check(kept is Delivered) { "a durable producer sends only Delivered commands, not $kept" }
        // A copy that finds the entity's mailbox full is not lost: it is sent again on the next resend.
        @Suppress("UNCHECKED_CAST")
        route(to).handed(kept.redeliver(Delivery(producer, to, sequence, ctx.self)) as M)
    }

    fun resend(ctx: Ctx<Any>, outboxes: Outboxes<ByteArray>) = outboxes.heads().forEach {
        meters.resent.increment()
        perform(ctx, it)
    }
}

/**
 * The journal threw, on [message] or on a replay: [producer] is [down] until a replay succeeds, and a command that
 * was not written is answered [Unwritten] and gives its room back.
 */
private fun threw(
    producer: String,
    down: AtomicReference<Throwable?>,
    held: HeldRoom,
    message: Any?,
    cause: Exception,
) {
    if (down.getAndSet(cause) == null) {
        logWarn("producer $producer cannot write to its journal, and answers Unwritten until it can: $cause")
    }
    if (message is KeepDurably && !message.isAnswered) {
        held.refund()
        message.unwritten(cause)
    }
}

/**
 * A durable producer's [room], moved by what [Holding] says: the commands that hold it are each kept here, and each
 * recovered that there was room for, so room never exceeds keep and a drain waits for what was recovered as well as
 * what was sent.
 */
private class HeldRoom(private val room: Semaphore, private val keep: Int, private val meters: ProducerMeters) {
    private val holding = AtomicReference(Holding())

    /** [to]'s [sequence] is written, and holds the room `send` took for it. */
    fun kept(to: String, sequence: Long) = moved { it.kept(to, sequence) to 0 }

    /** Room for each of [outboxes]' commands that holds none yet, as far as there is room. */
    fun claim(outboxes: Outboxes<*>) = moved { holding ->
        val wanted = holding.unheld(outboxes)
        var free = 0
        while (free < wanted && room.tryAcquire()) free++
        holding.claim(outboxes, free).first to 0
    }

    /** [to]'s [sequence] is confirmed, leaving [outboxes]: its room is handed on, or released. */
    fun confirmed(outboxes: Outboxes<*>, to: String, sequence: Long) = moved { holding ->
        val (after, released) = holding.confirmed(outboxes, to, sequence)
        after to if (released) 1 else 0
    }

    /** A command `send` took room for was not written, and gives it back. */
    fun refund() = moved { it to 1 }

    private fun moved(step: (Holding) -> Pair<Holding, Int>) {
        val (after, released) = step(holding.get())
        holding.set(after)
        room.release(released)
        // Room taken is what `send` has kept and no entity has confirmed yet.
        meters.unconfirmed.set((keep - room.availablePermits()).toDouble())
    }
}

/**
 * This behaviour, telling [failed] of each throw from a turn, with its message, or from a start, with none, on the
 * throw's way to supervision. A raise is not a throw, and goes on untold.
 */
private fun <M : Any, S, E> Behaviour<M, S, E>.onThrow(failed: (M?, Exception) -> Unit): Behaviour<M, S, E> {
    val turn = turn
    val start = start
    return Behaviour(
        initial = initial,
        turn = { ctx, state, message -> told(failed, message) { turn(ctx, state, message) } },
        signal = signal,
        start = start?.let { { ctx, state -> told(failed, null) { start(ctx, state) } } },
        turns = turns,
        batch = batch,
    )
}

/** What [body] returns or fails with; what it throws is told to [failed] and thrown on. */
// A journal may throw anything, and whatever it threw goes on to supervision unchanged.
@Suppress("TooGenericExceptionCaught")
private inline fun <M : Any, S, E> Fail<E>.told(
    failed: (M?, Exception) -> Unit,
    message: M?,
    crossinline body: Fail<E>.() -> Next<S>,
): Next<S> =
    try {
        attempt<E, Next<S>> { body(this) }
    } catch (thrown: Exception) {
        failed(message, thrown)
        throw thrown
    }.bind()

/** [stored] after [event], through the same [Outboxes] steps a live command takes. */
private fun applied(stored: Stored, event: OutboxEvent): Stored = when (event) {
    is OutboxEvent.Kept -> Stored(
        stored.outboxes.kept(event.to, event.sequence, event.bytes).first,
        stored.next + (event.to to event.sequence + 1),
    )

    // Only a head is confirmed, so a Done the journal holds is for a head; any other is passed over, and what it
    // names is sent again, never lost.
    is OutboxEvent.Done -> stored.copy(
        outboxes = stored.outboxes.confirmed(event.to, event.sequence)?.first ?: stored.outboxes,
    )
}

private object OutboxEventCodec : EventCodec<OutboxEvent> {
    override fun encode(event: OutboxEvent): ByteArray = written { data ->
        when (event) {
            is OutboxEvent.Kept -> {
                data.writeInt(KEPT)
                data.writeUTF(event.to)
                data.writeLong(event.sequence)
                data.writeInt(event.bytes.size)
                data.write(event.bytes)
            }

            is OutboxEvent.Done -> {
                data.writeInt(DONE)
                data.writeUTF(event.to)
                data.writeLong(event.sequence)
            }
        }
    }

    override fun decode(bytes: ByteArray): OutboxEvent = read(bytes) { data ->
        when (val tag = data.readInt()) {
            KEPT -> OutboxEvent.Kept(data.readUTF(), data.readLong(), ByteArray(data.readInt()).also(data::readFully))
            DONE -> OutboxEvent.Done(data.readUTF(), data.readLong())
            else -> error("no outbox event has the tag $tag")
        }
    }
}

private object OutboxesCodec : StateCodec<Stored> {
    // Each entity numbered so far, its next number, and what it keeps: as before spec 0146, so snapshots read back.
    override fun encode(state: Stored): ByteArray = written { data ->
        data.writeInt(state.next.size)
        state.next.forEach { (to, next) ->
            val kept = state.outboxes.queues[to].orEmpty()
            data.writeUTF(to)
            data.writeLong(next)
            data.writeInt(kept.size)
            kept.forEach { (sequence, bytes) ->
                data.writeLong(sequence)
                data.writeInt(bytes.size)
                data.write(bytes)
            }
        }
    }

    override fun decode(bytes: ByteArray): Stored = read(bytes) { data ->
        val entities = List(data.readInt()) {
            Triple(
                data.readUTF(),
                data.readLong(),
                List(data.readInt()) { KeptCommand(data.readLong(), ByteArray(data.readInt()).also(data::readFully)) },
            )
        }
        Stored(
            Outboxes(entities.filter { it.third.isNotEmpty() }.associate { (to, _, kept) -> to to kept }),
            entities.associate { (to, next, _) -> to to next },
        )
    }
}

private fun written(write: (DataOutputStream) -> Unit): ByteArray =
    ByteArrayOutputStream().also { out -> DataOutputStream(out).use(write) }.toByteArray()

private fun <A> read(bytes: ByteArray, read: (DataInputStream) -> A): A =
    DataInputStream(ByteArrayInputStream(bytes)).use(read)
