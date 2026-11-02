package dev.surefold.stream

import java.time.Instant
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What the pulls with time in them wait on: a test's clock, where workers take turns ([Turns]), or the
 * run's own clock on Forks ([RealTime]). Either way a pull parks until an instant or until something
 * another fork does makes it ready, and whoever makes it ready says so with [wake].
 */
internal interface Timeline {
    val stopped: Boolean

    fun now(): Instant

    fun park(until: Instant? = null, ready: () -> Boolean = { false })

    fun fork(body: () -> Unit)

    /** Something a parked fork's `ready` reads has changed. */
    fun wake()
}

/**
 * The run's clock on Forks: surefold's `clock`, as it was when the run started. A park waits on real time
 * for the instant it is given, or until woken, and a stop's interrupt ends it. A fork is one of the
 * run's, let go of when the run ends.
 */
internal class RealTime(private val releases: Releases) : Timeline {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    override val stopped: Boolean get() = false

    override fun now(): Instant = releases.clock.now()

    override fun park(until: Instant?, ready: () -> Boolean) {
        lock.withLock {
            var due = false
            while (!ready() && !due) {
                val left = until?.let { java.time.Duration.between(now(), it).toNanos() }
                when {
                    left == null -> changed.await()
                    left <= 0 -> due = true
                    else -> changed.awaitNanos(left)
                }
            }
        }
    }

    override fun fork(body: () -> Unit) {
        releases.add(InFlight(releases.on, body)::cancel)
    }

    override fun wake() = lock.withLock { changed.signalAll() }
}
