package dev.surefold.stream

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.LockSupport

/**
 * The answers of an unordered window (spec 0170), arriving from whichever thread completed them, for the one
 * pull that takes them; and the room the one feeder waits for, [window] answers in flight at most.
 *
 * Either side parks as [Ring]'s do: it names itself, then looks again, and the other side looks for a name
 * only after its own change, which the queue's and the counter's atomic updates order before the look. The
 * feeder is woken once a batch of room has been made, or once nothing is in flight, not on every answer.
 */
internal class Arrivals(private val window: Int) {
    private val arrived = ConcurrentLinkedQueue<Any>()
    private val inFlight = AtomicInteger()
    private val batch = maxOf(1, minOf(window / BATCHES, MAX_BATCH))

    @Volatile private var parkedTaker: Thread? = null

    @Volatile private var parkedFeeder: Thread? = null

    /** The feeder's: waits until fewer than [window] are in flight, and holds a place for one more. */
    fun reserve() {
        if (Thread.interrupted()) throw InterruptedException()
        while (inFlight.get() >= window) parkFeeder { inFlight.get() >= window }
        inFlight.incrementAndGet()
    }

    /** The feeder's: gives back a place [reserve] held that nothing was started in. */
    fun unreserve() {
        inFlight.decrementAndGet()
    }

    /** The feeder's: waits until every answer started has been taken and [released]. */
    fun awaitDrained() {
        while (inFlight.get() > 0) parkFeeder { inFlight.get() > 0 }
    }

    /** Any thread's: an answer, or how the feed ended, for the pull. */
    fun arrive(answer: Any) {
        arrived.offer(answer)
        parkedTaker?.let { taker ->
            parkedTaker = null
            LockSupport.unpark(taker)
        }
    }

    /** The pull's: the next arrival, waiting while there is none. */
    fun take(): Any {
        if (Thread.interrupted()) throw InterruptedException()
        var yielded = 0
        while (true) {
            arrived.poll()?.let { return it }
            if (yielded++ < YIELDS_BEFORE_WAITING) Thread.yield() else parkTaker()
        }
    }

    /** The pull's, once it has taken an answer that held a place. */
    fun released() {
        val left = inFlight.decrementAndGet()
        if (left == 0 || window - left >= batch) {
            parkedFeeder?.let { feeder ->
                parkedFeeder = null
                LockSupport.unpark(feeder)
            }
        }
    }

    private fun parkTaker() {
        parkedTaker = Thread.currentThread()
        if (arrived.isEmpty()) LockSupport.park(this)
        parkedTaker = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    private inline fun parkFeeder(stillWaiting: () -> Boolean) {
        parkedFeeder = Thread.currentThread()
        if (stillWaiting()) LockSupport.park(this)
        parkedFeeder = null
        if (Thread.interrupted()) throw InterruptedException()
    }

    private companion object {
        const val BATCHES = 4
        const val MAX_BATCH = 64
    }
}

/**
 * Upstream's elements, each started by [start] once there is room for it, then, once every answer started has
 * been taken, how upstream ended: the end arrives after the last answer, whatever order the answers came in.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, after what came first.
@Suppress("TooGenericExceptionCaught")
internal fun feedUnordered(up: Pull, arrivals: Arrivals, start: (Any) -> Unit) {
    try {
        val ended = try {
            while (true) {
                arrivals.reserve()
                val a = up.next()
                if (a == null) {
                    arrivals.unreserve()
                    break
                }
                start(a)
            }
            Fed.Done
        } catch (stopped: InterruptedException) {
            throw stopped
        } catch (thrown: Throwable) {
            arrivals.unreserve()
            Fed.Threw(thrown)
        }
        arrivals.awaitDrained()
        arrivals.arrive(ended)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    }
}
