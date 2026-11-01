package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.fail.Failed
import java.util.concurrent.CancellationException
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO

/**
 * How many calls may be inside one dependency at once (spec 0171). [fixed] is a number somebody picked;
 * [adaptive] finds its own from how long calls take, growing while they take no longer than they do unloaded
 * and shrinking once they queue. One value shared by every caller of a dependency bounds them all together.
 */
class Limit private constructor(
    val name: String,
    initial: Int,
    /** The most the limit will ever allow, and so the most a window built on it needs room for. */
    val max: Int,
    private val adapting: Vegas?,
) {
    private val lock = ReentrantLock()
    private val room = lock.newCondition()
    private var allowed = initial
    private var using = 0

    /** What the limit allows now. */
    val current: Int get() = lock.withLock { allowed }

    /** Calls inside the limit now. */
    val inFlight: Int get() = lock.withLock { using }

    /**
     * Runs [block] once the limit has room for it, waiting until then. How long [block] took is what an adaptive
     * limit learns from; a block that throws counts as a call the dependency failed, unless it was interrupted or
     * cancelled, which says nothing about the dependency. Nor does a `fail` passing through: it is the call's own
     * answer, so a lookup that answers "not found" is not taken for overload.
     */
    fun <A> withPermit(block: () -> A): A = measured(acquire(), block = block)

    /**
     * Room for one call if it comes within [wait], and how many calls are inside with it; null if none came. The
     * call that was let in then runs through [measured], which gives the room back.
     */
    internal fun admit(wait: Duration): Int? = lock.withLock {
        var left = wait.coerceAtLeast(ZERO).inWholeNanoseconds
        while (using >= allowed) {
            if (left <= 0) return null
            left = room.awaitNanos(left)
        }
        using++
        publish()
        using
    }

    /** [block] run as a call let in with [found] calls inside: timed, learnt from, and its room given back. */
    internal fun <A> measured(
        found: Int,
        failedIf: (A) -> Boolean = { false },
        block: () -> A,
    ): A {
        val started = System.nanoTime()
        var outcome = Outcome.FAILED
        try {
            return block().also { outcome = if (failedIf(it)) Outcome.FAILED else Outcome.ANSWERED }
        } catch (stopped: InterruptedException) {
            outcome = Outcome.UNMEASURED
            throw stopped
        } catch (cancelled: CancellationException) {
            outcome = Outcome.UNMEASURED
            throw cancelled
        } catch (failed: Failed) {
            outcome = Outcome.UNMEASURED
            throw failed
        } finally {
            release(System.nanoTime() - started, found, outcome)
        }
    }

    /** Waits for room, and answers how many calls are inside with this one. */
    private fun acquire(): Int = lock.withLock {
        while (using >= allowed) room.await()
        using++
        publish()
        using
    }

    private fun release(tookNanos: Long, found: Int, outcome: Outcome) = lock.withLock {
        using--
        val before = allowed
        if (adapting != null && outcome != Outcome.UNMEASURED) {
            allowed = adapting.sample(tookNanos, found, dropped = outcome == Outcome.FAILED)
        }
        if (allowed > before) room.signalAll() else room.signal()
        publish()
    }

    private fun publish() {
        gauge("surefold.limit.current", "name" to name).set(allowed.toDouble())
        gauge("surefold.limit.in_use", "name" to name).set(using.toDouble())
    }

    private enum class Outcome { ANSWERED, FAILED, UNMEASURED }

    companion object {
        /** Today's behaviour as a value: at most [n] at once, always. */
        fun fixed(n: Int, name: String = "fixed"): Limit {
            require(n >= 1) { "Limit \"$name\" must let at least one call in, not $n." }
            return Limit(name, n, n, adapting = null)
        }

        /** Starts at [initial] and finds its own level between [min] and [max] from how long calls take. */
        fun adaptive(name: String, initial: Int = 20, min: Int = 1, max: Int = 1_000): Limit {
            require(min in 1..max) { "Limit \"$name\" needs 1 <= min <= max, not min $min and max $max." }
            require(initial in min..max) { "Limit \"$name\" must start between $min and $max, not at $initial." }
            return Limit(name, initial, max, Vegas(initial, min, max))
        }
    }
}
