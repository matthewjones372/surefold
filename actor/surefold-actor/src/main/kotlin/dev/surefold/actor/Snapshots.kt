package dev.surefold.actor

import java.util.concurrent.ConcurrentHashMap

/** A persistent actor's state as bytes, as it was after the event numbered [sequence]. */
class Snapshot(val sequence: Long, val bytes: ByteArray)

/**
 * Where persistent actors' snapshots are kept (spec 0074): one per id, the newest saved. A snapshot only spares a
 * recovery the events before it; the journal still holds every one. A store that cannot write throws.
 */
interface SnapshotStore {
    /** Keeps [bytes] as [id]'s state after event [sequence], unless the store already holds a newer one for [id]. */
    fun save(id: PersistenceId, sequence: Long, bytes: ByteArray)

    /** [id]'s newest snapshot, or null when none was saved. */
    fun latest(id: PersistenceId): Snapshot?
}

/** Snapshots in memory, for tests and for a service that can lose them. It keeps copies of the bytes. */
class InMemorySnapshots : SnapshotStore {
    private val kept = ConcurrentHashMap<PersistenceId, Snapshot>()

    override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) {
        val saved = Snapshot(sequence, bytes.copyOf())
        kept.merge(id, saved) { held, newer -> newer.takeIf { it.sequence > held.sequence } ?: held }
    }

    override fun latest(id: PersistenceId): Snapshot? = kept[id]?.let { Snapshot(it.sequence, it.bytes.copyOf()) }
}
