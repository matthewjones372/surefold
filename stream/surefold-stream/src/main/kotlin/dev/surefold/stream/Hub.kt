package dev.surefold.stream

import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Why a [Hub] turned an element away. The element was given to no subscriber, so it can be offered again. */
sealed interface HubRefused {

    /** A subscriber's queue has no room: it is reading more slowly than the hub is published to. */
    data object Full : HubRefused

    /** The hub has closed and takes nothing more. */
    data object Closed : HubRefused
}

/**
 * One publisher's elements, read by every subscriber in the same process, on any backend.
 *
 * Each subscriber has a queue of its own, holding at most [capacity]. [publish] never waits: an element
 * goes to every subscriber or, when one of them is full, to none, and the caller is told which. What is
 * published before anyone has subscribed is held, up to [capacity], for the first subscriber; once there
 * has been one, an element published while there is none is accepted and goes nowhere, as a broadcast to
 * nobody does.
 *
 * A subscriber is a run of [subscribe], from the moment it starts until it ends however it ends, so a
 * subscriber that stops no longer holds the hub back.
 */
class Hub<A : Any>(val capacity: Int = DEFAULT_CAPACITY) : AutoCloseable {

    init {
        require(capacity > 0) { "a hub holds at least one element for each subscriber, not $capacity" }
    }

    /**
     * What the hub holds, as one value: replaced whole, and only under [lock], so that two publishers
     * cannot interleave and every subscriber sees elements in the same order.
     */
    private data class Held<A : Any>(
        val early: List<A> = emptyList(),
        val queues: List<Hub<A>.Subscriber> = emptyList(),
        val subscribedOnce: Boolean = false,
        val closed: Boolean = false,
    )

    private val lock = ReentrantLock()
    private val held = AtomicReference(Held<A>())

    /** How many subscriptions are running now. */
    val subscribers: Int get() = held.get().queues.size

    /** [element] to every subscriber, or to none of them with the reason. Never waits for room. */
    fun publish(element: A): Either<HubRefused, A> = lock.withLock {
        val now = held.get()
        when {
            now.closed -> HubRefused.Closed.left()
            !now.subscribedOnce && now.early.size >= capacity -> HubRefused.Full.left()
            !now.subscribedOnce -> element.right().also { held.set(now.copy(early = now.early + element)) }
            now.queues.any { it.size >= capacity } -> HubRefused.Full.left()
            else -> element.right().also { now.queues.forEach { queue -> queue.put(element) } }
        }
    }.also { if (it.isRight()) held.get().queues.forEach { queue -> queue.waiting?.changed() } }

    /**
     * Everything published from the moment a run of this starts, in order; the first run ever also gets
     * what was held for it. A description, so each run is a subscriber of its own. It ends `Done` once the
     * hub has closed and it has read what it was sent.
     */
    fun subscribe(): Stream<Nothing, A> =
        Stream.blocking(open = { register() }, next = { it.take() }, wake = { it.end() }, close = { unregister(it) })

    /** Takes nothing more, and ends every subscription once it has read what it holds. */
    override fun close() = lock.withLock {
        held.updateAndGet { it.copy(closed = true) }.queues.forEach { it.end() }
    }

    private fun register(): Subscriber = lock.withLock {
        val now = held.get()
        Subscriber().also { queue ->
            // The first subscriber ever is handed what was held for it; later ones start from now.
            now.early.forEach(queue::put)
            if (now.closed) queue.end()
            held.set(now.copy(early = emptyList(), queues = now.queues + queue, subscribedOnce = true))
        }
    }

    private fun unregister(queue: Subscriber) =
        lock.withLock { held.updateAndGet { it.copy(queues = it.queues - queue) } }

    /** One subscriber's queue: the elements sent to it, then [End] once the hub closes or the run stops. */
    private inner class Subscriber {
        private val queue = LinkedBlockingQueue<Any>()

        /** How the run that opened this waits, when its backend must know: see [Waiting]. */
        val waiting: Waiting? = Waiting.here()

        val size: Int get() = queue.size

        fun put(element: A) = queue.put(element)

        fun end() {
            queue.put(End)
            waiting?.changed()
        }

        /** The next element, waiting for one; `null` once the subscription has ended or its run stopped. */
        @Suppress("UNCHECKED_CAST")
        fun take(): A? {
            val next = if (waiting == null) {
                queue.take()
            } else {
                waiting.until { queue.isNotEmpty() }
                queue.poll() ?: return null
            }
            return if (next === End) null.also { queue.put(End) } else next as A
        }
    }

    /** What ends a subscriber's queue. Put back when read, so a read after the end ends again. */
    private object End

    companion object {
        /** A queue for each subscriber deep enough for a burst, small enough that a stalled one shows. */
        const val DEFAULT_CAPACITY: Int = 256
    }
}

/**
 * Publishes each element to [hub] in turn, as it passes. An element the hub refuses ends the stream with
 * the refusal in its failure type, where the publisher decides whether to wait and try again.
 */
fun <A : Any> Stream<Nothing, A>.publishTo(hub: Hub<A>): Stream<HubRefused, A> =
    mapOrFail { element -> hub.publish(element).bind() }
