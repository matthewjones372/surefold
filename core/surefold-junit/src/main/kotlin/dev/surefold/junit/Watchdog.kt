package dev.surefold.junit

import dev.surefold.clock
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration

private const val RUNNING = 0
private const val DONE = 1
private const val FIRED = 2

/**
 * Runs [test] on this thread, held to [duration] by a platform thread of its own (spec 0310). A virtual watchdog shares
 * the scheduler with the test it watches: on one carrier, a test that spins without yielding never lets it run. When
 * [duration] passes first, the watchdog dumps every thread while the test's are still where they hung (spec 0308), then
 * interrupts the test, and this throws [TimeoutException] once the test has unwound.
 */
@Suppress("TooGenericExceptionCaught") // The test's own failure is rethrown unchanged, or kept as a suppressed cause.
internal fun withinTimeout(duration: Duration, test: () -> Unit) {
    // Read here, where the test's bindings are: a TestClock the test runs under holds the watchdog to it too.
    val waitsOn = clock.get()
    val tested = Thread.currentThread()
    val state = AtomicInteger(RUNNING)
    val watchdog =
        Thread.ofPlatform().daemon().name("surefold-junit-timeout").start {
            try {
                waitsOn.sleep(duration)
            } catch (_: InterruptedException) {
                return@start // The test answered in time.
            }
            if (state.compareAndSet(RUNNING, FIRED)) {
                ThreadDump.toStderr("a test timed out after $duration")
                tested.interrupt()
            }
        }
    var failure: Throwable? = null
    try {
        test()
    } catch (thrown: Throwable) {
        failure = thrown
    }
    if (state.compareAndSet(RUNNING, DONE)) {
        watchdog.interrupt()
        failure?.let { throw it }
        return
    }
    // The watchdog fired: wait until its interrupt has landed, then clear it, so none reaches what runs here next.
    while (watchdog.isAlive) {
        try {
            watchdog.join()
        } catch (_: InterruptedException) {
            // The watchdog's own interrupt, arriving while this waits for it to finish.
        }
    }
    Thread.interrupted()
    throw TimeoutException("no answer within $duration").also { timedOut -> failure?.let(timedOut::addSuppressed) }
}
