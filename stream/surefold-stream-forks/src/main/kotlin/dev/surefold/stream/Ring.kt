package dev.surefold.stream

import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.locks.LockSupport

// Padding, so the producer's index and the consumer's never share a cache line: each side writes its own
// on every element, and a shared line would bounce between their cores on every one of them.
@Suppress("unused")
internal open class RingPadBeforeTail {
    @JvmField var p01 = 0L

    @JvmField var p02 = 0L

    @JvmField var p03 = 0L

    @JvmField var p04 = 0L

    @JvmField var p05 = 0L

    @JvmField var p06 = 0L

    @JvmField var p07 = 0L
}

internal open class RingTail : RingPadBeforeTail() {
    @Volatile @JvmField var tail = 0L

    // The producer's last sight of the consumer's index, kept off the consumer's line for the same reason.
    @JvmField var headSeen = 0L
}

@Suppress("unused")
internal open class RingPadBeforeHead : RingTail() {
    @JvmField var p11 = 0L

    @JvmField var p12 = 0L

    @JvmField var p13 = 0L

    @JvmField var p14 = 0L

    @JvmField var p15 = 0L

    @JvmField var p16 = 0L

    @JvmField var p17 = 0L
}

internal open class RingHead : RingPadBeforeHead() {
    @Volatile @JvmField var head = 0L

    // The consumer's last sight of the producer's index, kept off the producer's line for the same reason.
    @JvmField var tailSeen = 0L
}

@Suppress("unused")
internal open class RingPadAfterHead : RingHead() {
    @JvmField var p21 = 0L

    @JvmField var p22 = 0L

    @JvmField var p23 = 0L

    @JvmField var p24 = 0L

    @JvmField var p25 = 0L

    @JvmField var p26 = 0L

    @JvmField var p27 = 0L
}

/**
 * A ring of [capacity] between one producer and one consumer (spec 0172), which wakes either side only when it must:
 * the consumer when the ring stops being empty, the producer once a batch of room has been made.
 *
 * Either side yields a few times and then parks the same way: it names itself, fences, and looks once more before
 * parking, so a wake can only be missed if the other side's fence is missing too. The producer fences on every put,
 * since one element may be all the consumer gets for a long time. The consumer fences only where its index crosses a
 * multiple of the batch: a parked producer left the ring full, so the consumer crosses one before it empties.
 */
internal class Ring(private val capacity: Int) : RingPadAfterHead() {
    init {
        require(capacity >= 1) { "a ring needs room for at least one element, not $capacity" }
        require(capacity <= MAX_CAPACITY) { "a ring holds at most $MAX_CAPACITY elements, not $capacity" }
    }

    // A power of two, so finding a slot is a mask rather than a division on every element; the indices still
    // let no more than [capacity] of them be in use.
    private val slots = arrayOfNulls<Any>(Integer.highestOneBit(capacity - 1).shl(1).coerceAtLeast(1))
    private val slotMask = slots.size - 1L

    // A quarter of the ring, but never so much that a wide window idles: with 10,000 calls in flight, waiting
    // for 2,500 to finish before starting more costs a quarter of the concurrency the caller asked for.
    private val batchMask = Integer.highestOneBit(maxOf(1, minOf(capacity / BATCHES, MAX_BATCH))).toLong() - 1

    @Volatile private var parkedProducer: Thread? = null

    @Volatile private var parkedConsumer: Thread? = null

    private fun slot(index: Long) = (index and slotMask).toInt()

    /** Waits while the ring is full. Throws [InterruptedException] if interrupted, whether it had to wait or not. */
    fun put(element: Any) {
        // As the consumer does on an empty ring: a park and an unpark cost more than the room takes to appear.
        awaitRoom(YIELDS_BEFORE_PARKING)
        offer(element)
    }

    /**
     * Waits while the ring is full, for a producer that must know there is room before it makes the element. A window's
     * room comes only as its bodies finish, so it parks at once rather than [yields] first.
     */
    fun awaitRoom(yields: Int = 0) {
        if (Thread.interrupted()) throw InterruptedException()
        val t = tail
        var yielded = 0
        while (t - headSeen >= capacity && t - head.also { headSeen = it } >= capacity) {
            if (yielded++ < yields) Thread.yield() else parkUntilRoom(t)
        }
    }

    /** Adds [element] after [awaitRoom] said there was room: it never waits, and an interrupt cannot lose it. */
    fun offer(element: Any) {
        val t = tail
        // Against the last head awaitRoom saw, not the live one: reading the consumer's index on every element
        // would pull its cache line across for each put, which is the cost the cached copy exists to avoid.
        check(t - headSeen < capacity) { "offer without room" }
        slots[slot(t)] = element
        TAIL.setRelease(this, t + 1)
        VarHandle.fullFence()
        parkedConsumer?.let { consumer ->
            parkedConsumer = null
            LockSupport.unpark(consumer)
        }
    }

    /** Waits while the ring is empty. Throws [InterruptedException] if interrupted, whether it had to wait or not. */
    fun take(): Any {
        if (Thread.interrupted()) throw InterruptedException()
        var yielded = 0
        while (true) {
            poll()?.let {
                return it
            }
            // A yield lets a producer on the same carrier run, and is far cheaper than a park and an unpark.
            if (yielded++ < YIELDS_BEFORE_PARKING) Thread.yield() else parkUntilNotEmpty()
        }
    }

    /** The next element, left in the ring and so still counted against its capacity, waiting while there is none. */
    fun awaitHead(): Any {
        if (Thread.interrupted()) throw InterruptedException()
        var yielded = 0
        while (true) {
            peek()?.let {
                return it
            }
            // A yield lets a producer on the same carrier run, and is far cheaper than a park and an unpark.
            if (yielded++ < YIELDS_BEFORE_PARKING) Thread.yield() else parkUntilNotEmpty()
        }
    }

    /** The next element, left in the ring, or `null` at once if the ring is empty. */
    fun peek(): Any? {
        val h = head
        return if (h >= tailSeen && h >= tail.also { tailSeen = it }) null else slots[slot(h)]
    }

    /** Whether the ring is empty, against the producer's live index: for a consumer about to park. */
    fun isEmpty(): Boolean = head >= tail

    /**
     * For a consumer reading several rings, which parks on all of them at once: names it as parked on this one, or
     * clears the name with `null`. The caller fences after naming itself and looks again before parking, as [take]
     * does.
     */
    fun nameConsumer(consumer: Thread?) {
        parkedConsumer = consumer
    }

    private fun parkUntilRoom(t: Long) {
        parkedProducer = Thread.currentThread()
        VarHandle.fullFence()
        if (t - head >= capacity) LockSupport.park(this)
        parkedProducer = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    private fun parkUntilNotEmpty() {
        parkedConsumer = Thread.currentThread()
        VarHandle.fullFence()
        if (head >= tail) LockSupport.park(this)
        parkedConsumer = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    /** The next element, or `null` at once if the ring is empty. */
    fun poll(): Any? {
        val h = head
        if (h >= tailSeen && h >= tail.also { tailSeen = it }) return null
        val i = slot(h)
        val element = slots[i]
        slots[i] = null
        HEAD.setRelease(this, h + 1)
        if ((h + 1) and batchMask == 0L) {
            VarHandle.fullFence()
            parkedProducer?.let { producer ->
                parkedProducer = null
                LockSupport.unpark(producer)
            }
        }
        return element
    }

    private companion object {
        const val BATCHES = 4
        const val MAX_BATCH = 64
        const val YIELDS_BEFORE_PARKING = 4
        const val MAX_CAPACITY = 1 shl 30

        val TAIL: VarHandle =
            MethodHandles.lookup().findVarHandle(RingTail::class.java, "tail", Long::class.javaPrimitiveType)
        val HEAD: VarHandle =
            MethodHandles.lookup().findVarHandle(RingHead::class.java, "head", Long::class.javaPrimitiveType)
    }
}
