package dev.surefold.cache

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * A cache's entries in this process, at most [capacity] of them, in two segments as W-TinyLFU keeps them
 * (Einziger, Friedman and Manes, 2017). A new entry starts on probation; read again, it moves to the protected
 * segment, which holds up to four fifths of [capacity]. Past [capacity], a new entry must have been asked for more
 * often lately than the least recently read entry on probation, by [Sketch], or it is answered and not kept. So a scan
 * of keys read once only ever competes with itself, and cannot flush the keys read all day.
 *
 * Mutations return the entries they remove, so the caller can notify their owners outside its own reservations.
 */
internal class Memory<K : Any, T>(private val capacity: Int) {
    class Written<K, T>(val replaced: T?, val evicted: Pair<K, T>?)

    init {
        require(capacity >= 1) { "a cache holds at least one entry, not $capacity" }
    }

    private val lock = ReentrantLock()
    private val probation = LinkedHashMap<K, T>(INITIAL, LOAD, true)
    private val protected = LinkedHashMap<K, T>(INITIAL, LOAD, true)
    private val protectedCapacity = (capacity * PROTECTED_SHARE).toInt()
    private val sketch = Sketch(capacity)

    /** [key]'s entry, read now: it counts as an ask, and as the most recent read, and moves off probation. */
    fun read(key: K): T? = lock.withLock {
        sketch.increment(key)
        protected[key] ?: probation.remove(key)?.also { promote(key, it) }
    }

    /** [key]'s entry, looked at without counting as an ask or a read. */
    fun peek(key: K): T? = lock.withLock { protected[key] ?: probation[key] }

    /**
     * Keeps [value] for [key], pushing out the least recently read entry on probation if [key] has been asked for
     * more often, and answers both the replacement and the capacity victim, if any.
     */
    fun write(key: K, value: T): Written<K, T> {
        var pushedOut: Pair<K, T>? = null
        val replaced = lock.withLock {
            when {
                key in protected -> protected.put(key, value)

                key in probation -> probation.put(key, value)

                probation.size + protected.size < capacity -> probation.put(key, value)

                else -> {
                    pushedOut = admit(key, value)
                    null
                }
            }
        }
        return Written(replaced, pushedOut)
    }

    fun remove(key: K): T? = lock.withLock { protected.remove(key) ?: probation.remove(key) }

    fun clear(): List<Pair<K, T>> {
        return lock.withLock {
            (protected.toList() + probation.toList()).also {
                protected.clear()
                probation.clear()
            }
        }
    }

    val size: Int get() = lock.withLock { probation.size + protected.size }

    // Read twice: protected, and the protected segment's least recent goes back on probation if it is over its share.
    private fun promote(key: K, value: T) {
        protected[key] = value
        if (protected.size > protectedCapacity) {
            val (demoted, held) = protected.entries.first().toPair()
            protected.remove(demoted)
            probation[demoted] = held
        }
    }

    // A full memory: the new entry for probation's least recent, if the sketch says it is asked for more often.
    private fun admit(key: K, value: T): Pair<K, T>? {
        val from = probation.ifEmpty { protected }
        val (victim, held) = from.entries.first().toPair()
        if (sketch.frequency(key) <= sketch.frequency(victim)) return null
        from.remove(victim)
        probation[key] = value
        return victim to held
    }

    private companion object {
        const val INITIAL = 16
        const val LOAD = 0.75f
        const val PROTECTED_SHARE = 0.8
    }
}
