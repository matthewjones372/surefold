package dev.surefold.actor

import dev.surefold.Carriers
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Whose events these are: a kind of entity, and the id of one. */
data class PersistenceId(val kind: String, val id: String)

/** How an event becomes bytes for a journal, and back. No serialisation library is chosen here. */
interface EventCodec<E> {
    fun encode(event: E): ByteArray

    fun decode(bytes: ByteArray): E

    /**
     * The events [bytes] are read as: one, unless the codec upgrades an old event to several, as a `versioned` one may
     * (spec 0091). What replays or follows a journal reads through this.
     */
    fun decodeAll(bytes: ByteArray): List<E> = listOf(decode(bytes))
}

/**
 * One event as the journal keeps it: its place, counting from 1, and its bytes; with [metadata], what the append that
 * wrote it carried (spec 0123), where the journal keeps that.
 */
class StoredEvent(val sequence: Long, val bytes: ByteArray, val metadata: Map<String, String> = emptyMap())

/** A writer expected [expected] to be the last sequence number of [id], and it was [actual]: another wrote first. */
data class JournalConflict(val id: PersistenceId, val expected: Long, val actual: Long)

/**
 * Where persistent actors' events are kept, as bytes. An append names the sequence number its writer expects to follow,
 * so that of two writers for one id only one succeeds; a journal that cannot write throws.
 */
interface Journal {
    /** Appends [events] after [expected], 0 for none yet, and answers the new last sequence number. */
    fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long>

    /** The events of [id] from sequence number [from] on, in order. */
    fun read(id: PersistenceId, from: Long = 1): List<StoredEvent>
}

/** The events of [id], decoded by [codec], without the marks of deliveries (spec 0079). */
fun <E> Journal.events(id: PersistenceId, codec: EventCodec<E>): List<E> =
    read(id).filterNot { isDeliveryMark(it.bytes) }.flatMap { codec.decodeAll(it.bytes) }

/**
 * A journal that can let go of an entity's early events once a snapshot covers them (spec 0076). An interface beside
 * [Journal], so a journal that cannot delete need not say so.
 */
interface JournalPruning {
    /**
     * Deletes [id]'s events up to and including [sequence], and only those at or before [readTo] in the feed, but never
     * its newest: the next append is checked against it. What is left reads as before, from the first event kept. An
     * id's offsets grow with its sequence numbers, so what goes is always a prefix (spec 0077).
     */
    fun deleteTo(id: PersistenceId, sequence: Long, readTo: Long = Long.MAX_VALUE)
}

/**
 * A journal in memory, for tests and for a service that can lose its events. It keeps copies of the bytes, is a
 * [SlicedFeed] whose offsets count appended events (appends take turns, so no offset is seen before a smaller one), and
 * prunes.
 */
class InMemoryJournal : Journal, SlicedFeed, JournalPruning {
    private val kept = ConcurrentHashMap<PersistenceId, List<StoredEvent>>()
    private val feed = ConcurrentSkipListMap<Long, FeedEvent>()
    private val appending = ReentrantLock()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        appending.withLock {
            val before = kept[id].orEmpty()
            val last = before.lastOrNull()?.sequence ?: 0
            if (last != expected) return JournalConflict(id, expected, last).left()
            // What the appending thread carries, kept with each event as the JDBC journal keeps it (spec 0123).
            val carried = Carriers.capture()
            val stored = events.mapIndexed { i, bytes -> StoredEvent(expected + i + 1, bytes.copyOf(), carried) }
            kept[id] = before + stored
            stored.forEach { event ->
                val offset = (feed.lastEntry()?.key ?: 0) + 1
                feed[offset] = FeedEvent(offset, id, event.sequence, event.bytes, event.metadata)
            }
            (expected + events.size).right()
        }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> =
        kept[id]
            .orEmpty()
            .filter { it.sequence >= from }
            .map { StoredEvent(it.sequence, it.bytes.copyOf(), it.metadata) }

    override fun deleteTo(id: PersistenceId, sequence: Long, readTo: Long) {
        appending.withLock {
            val events = kept[id] ?: return
            val read = feed.headMap(readTo, true).values.filter { it.id == id }.maxOfOrNull { it.sequence } ?: 0
            val upTo = minOf(sequence, read, events.last().sequence - 1)
            kept[id] = events.filter { it.sequence > upTo }
            feed.values.removeIf { it.id == id && it.sequence <= upTo }
        }
    }

    override fun after(kind: String, offset: Long, limit: Int): List<FeedEvent> = after(kind, ALL, offset, limit)

    override fun after(kind: String, slices: IntRange, offset: Long, limit: Int): List<FeedEvent> =
        feed
            .tailMap(offset, false)
            .values
            .asSequence()
            .filter { it.id.kind == kind && (slices == ALL || Slices.of(it.id) in slices) }
            .take(limit)
            .map { FeedEvent(it.offset, it.id, it.sequence, it.bytes.copyOf(), it.metadata) }
            .toList()
}

/** Every slice. */
private val ALL = 0 until Slices.COUNT
