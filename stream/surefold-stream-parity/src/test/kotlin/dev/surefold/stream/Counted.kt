package dev.surefold.stream

import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * An executor that counts what it runs: each task on a virtual thread of its own, and how many have not
 * finished yet. Given to `Forks`, it sees every thread a run starts, so a run that leaves one behind
 * shows as a count that does not fall back to nothing.
 */
internal class Counted : Executor {

    private val running = AtomicInteger()

    val started = AtomicInteger()

    override fun execute(command: Runnable) {
        started.incrementAndGet()
        running.incrementAndGet()
        Thread.ofVirtual().start {
            try {
                command.run()
            } finally {
                running.decrementAndGet()
            }
        }
    }

    /**
     * How many tasks are still running once [within] has passed or none are, whichever is first. The
     * loop that completed a run's exit finishes just after it, so nothing counts as left behind until
     * it has had the chance.
     */
    fun leftRunning(within: Duration = 2.seconds): Int {
        val deadline = TimeSource.Monotonic.markNow() + within
        while (running.get() > 0 && deadline.hasNotPassedNow()) Thread.sleep(1)
        return running.get()
    }
}
