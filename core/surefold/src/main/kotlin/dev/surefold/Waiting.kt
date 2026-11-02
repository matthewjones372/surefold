package dev.surefold

import java.util.concurrent.locks.LockSupport
import kotlin.time.Duration

/**
 * Every place a surefold thread parks, wakes another or sleeps (spec 0042): a fork's waiters, a channel's and a
 * select's, a transaction waiting on a change, a combinator waiting on its forks, and the wall clock. One seam, so a
 * test that decides which thread goes next has one place to hold. Each call is the JDK's own, with nothing added.
 */
@OptIn(TurnSeam::class)
internal object Waiting {

    /** Parks this thread until an [unpark], an interrupt or a spurious wake; the caller checks why it woke. */
    fun park(blocker: Any) {
        val turns = turnsBound.get() ?: return LockSupport.park(blocker)
        if (!turns.parking()) return
        LockSupport.park(blocker)
        turns.woke()
    }

    /** Wakes [thread] from a [park], or lets its next one return at once. */
    fun unpark(thread: Thread?) {
        if (thread != null) turnsBound.get()?.waking(thread)
        LockSupport.unpark(thread)
    }

    /** Interrupts [thread], which may then run again. */
    fun interrupt(thread: Thread) {
        turnsBound.get()?.waking(thread)
        thread.interrupt()
    }

    /** What a fork about to be submitted starts with when turns are taken: null when they are not. */
    fun forking(): Any? = turnsBound.get()?.forking()

    /** A fork's body starts on this thread, waiting for its turn if [ticket] says turns are taken. */
    fun started(ticket: Any?) {
        if (ticket != null) turnsBound.get()?.started(ticket)
    }

    /** A fork's body that [started] with [ticket] has ended on this thread. */
    fun ended(ticket: Any?) {
        if (ticket != null) turnsBound.get()?.ended()
    }

    /**
     * Sleeps this thread for [duration] as `Thread.sleep` does: an interrupt, there before it or arriving during it,
     * throws [InterruptedException] with the flag cleared. A park until the deadline, so the clock waits here too.
     */
    fun sleep(duration: Duration) {
        val until = System.nanoTime() + duration.inWholeNanoseconds
        while (true) {
            if (Thread.interrupted()) throw InterruptedException("interrupted while sleeping")
            val left = until - System.nanoTime()
            if (left <= 0) return
            LockSupport.parkNanos(this, left)
        }
    }
}
