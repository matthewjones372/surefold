package io.github.matthewjones372.surefold.actor

import dev.surefold.Clock
import dev.surefold.TestClock
import java.time.Instant
import java.util.TreeSet
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** Where a timer's message goes once it falls due. */
internal fun interface Fired {
    fun fire(timer: Timer)
}

/**
 * One timer: its message, due [at], for [to] under [key], and again [every] after it is handled when periodic. It is
 * also what travels through the mailbox, so that the actor can tell the timer it is running under that key from one
 * cancelled or replaced since.
 */
internal class Timer(
    val at: Instant,
    val seq: Long,
    val key: Any,
    val message: Any,
    val to: Fired,
    val every: Duration? = null,
)

/** The key a receive timeout runs under, which no actor's own key can equal. */
internal object ReceiveTimeoutKey

/** The key of one timer that belongs to a state: equal to no other. */
internal class StateKey

/** Timers started with [state], under [keys]. */
internal class TimersOfState(val state: Any?, val keys: List<Any>)

/** Whether becoming [next] ends these timers: it does when [next] is of another class than their state. */
internal fun TimersOfState.endsAt(next: Any?): Boolean = next?.let { it::class } != state?.let { it::class }

/** Runs [timers] for [state], starting each under a key of its own with [start], and returns what it started. */
internal fun <M : Any> startTimers(
    state: Any?,
    timers: StateTimers<M>.() -> Unit,
    start: (key: Any, delay: Duration, message: M, every: Duration?) -> Unit,
): TimersOfState {
    val keys = mutableListOf<Any>()
    val starting = object : StateTimers<M> {
        override fun after(delay: Duration, message: M) = start(StateKey().also(keys::add), delay, message, null)

        override fun every(interval: Duration, message: M) {
            require(interval.isPositive()) { "a periodic timer needs a positive interval, was $interval" }
            start(StateKey().also(keys::add), interval, message, interval)
        }
    }
    starting.timers()
    return TimersOfState(state, keys.toList())
}

/** A receive timeout: [message], once nothing has arrived for [after]. */
internal class Silence(val after: Duration, val message: Any)

/**
 * Every timer of one scope's actors, on the scope's clock. On a [TestClock] the clock's moves deliver them, each
 * instant in turn, and a move returns once what fell due has been handled; on any other clock one thread, started
 * with the first timer, sleeps until the soonest and is woken early when a sooner one is started.
 */
internal class Wheel(private val clock: Clock, private val idle: () -> Unit) : TestClock.Waiter {
    private val lock = ReentrantLock()
    private val started = lock.newCondition()
    private val due = TreeSet(compareBy<Timer>({ it.at }, { it.seq }))
    private var seq = 0L

    // The thread that waits for the soonest timer, whether it is in the clock's sleep, where only an interrupt
    // reaches it, and whether the scope is closing. All three change only under the lock.
    private var thread: Thread? = null
    private var sleeping = false
    private var closing = false
    private val moves: AutoCloseable? = (clock as? TestClock)?.register(this)

    /** Starts a timer [delay] from now, and returns it so that it can be cancelled. */
    fun schedule(delay: Duration, key: Any, message: Any, to: Fired, every: Duration? = null): Timer {
        val at = clock.now().plusNanos(delay.inWholeNanoseconds)
        return lock.withLock {
            val timer = Timer(at, seq++, key, message, to, every)
            due += timer
            if (due.first() === timer) wake()
            timer
        }
    }

    fun cancel(timer: Timer) {
        lock.withLock { due.remove(timer) }
    }

    /** Stops the thread and lets go of the clock; a timer still to fall due never will. */
    fun close() {
        moves?.close()
        val running = lock.withLock {
            closing = true
            wake()
            thread
        }
        running?.joinThroughInterrupts()
    }

    override fun wakesAt(): Instant? = lock.withLock { due.firstOrNull()?.at }

    /** Delivers what the clock has reached, and waits for the scope to handle it, until nothing more is due. */
    override fun settle() {
        while (fireDue()) idle()
    }

    private fun wake() {
        when {
            moves != null -> Unit
            thread == null -> if (!closing) thread = Thread.ofVirtual().name("surefold-timers").start(::run)
            sleeping -> thread?.interrupt()
            else -> started.signal()
        }
    }

    /** Fires every timer due by now; whether there was one. */
    private fun fireDue(): Boolean {
        val now = clock.now()
        val fired = lock.withLock {
            generateSequence { due.firstOrNull()?.takeIf { it.at <= now }?.also(due::remove) }.toList()
        }
        fired.forEach { it.to.fire(it) }
        return fired.isNotEmpty()
    }

    private tailrec fun run() {
        val next = lock.withLock {
            while (!closing && due.isEmpty()) started.awaitUninterruptibly()
            if (closing) return
            sleeping = true
            due.first().at
        }
        val interrupted = sleepUntil(next)
        // Past this, nothing interrupts the thread, so an interrupt that landed as the sleep ended is cleared here.
        lock.withLock {
            sleeping = false
            Thread.interrupted()
        }
        // A clock that does not move, as a fixed one does not, would otherwise have this spin: wait out the time
        // on the wall instead, or until a timer is started.
        if (!fireDue() && !interrupted) {
            val left = java.time.Duration.between(clock.now(), next).toNanos()
            lock.withLock { if (!closing && left > 0) started.awaitNanos(left) }
        }
        run()
    }

    /** Sleeps on the clock until [at]; whether a sooner timer or the scope's close cut it short. */
    private fun sleepUntil(at: Instant): Boolean {
        val left = java.time.Duration.between(clock.now(), at)
        return try {
            if (!left.isNegative && !left.isZero) clock.sleep(left.toKotlinDuration())
            false
        } catch (woken: InterruptedException) {
            true
        }
    }
}

/** Joins however often the joining thread is interrupted, and interrupts it again afterwards. */
private fun Thread.joinThroughInterrupts() {
    var interrupted = false
    while (isAlive) {
        try {
            join()
        } catch (again: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}
