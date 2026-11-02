package dev.surefold.app

import dev.surefold.logError
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * What no thread caught, in the application's log rather than on stderr, where an operator's log shipping never sees
 * it (spec 0102). An actor that throws with no supervision stops, and its throw reaches here. When a database goes,
 * thousands of actors throw the same thing at once, so the first of each kind in a [window] is logged with its stack,
 * and the rest are counted and said with the next of that kind after the window.
 */
internal class Uncaught(
    private val window: Duration = 1.minutes,
    private val now: () -> Long = System::nanoTime,
) : Thread.UncaughtExceptionHandler {
    private class Seen(val since: Long, var more: Int = 0)

    private val seen = ConcurrentHashMap<String, Seen>()

    override fun uncaughtException(thread: Thread, thrown: Throwable) {
        val at = now()
        var said = -1
        seen.compute(kindOf(thrown)) { _, before ->
            if (before == null || at - before.since >= window.inWholeNanoseconds) {
                said = before?.more ?: 0
                Seen(at)
            } else {
                before.also { it.more++ }
            }
        }
        if (said < 0) return
        val where = thread.name.ifEmpty { "a virtual thread" }
        val more = if (said > 0) " ($said more like it in the $window before)" else ""
        logError("nothing caught this on $where: $thrown$more", thrown)
    }

    // Its class and where it was thrown: the same failure from a thousand actors is one kind, a different one is not.
    private fun kindOf(thrown: Throwable) = "${thrown.javaClass.name}@${thrown.stackTrace.firstOrNull()}"
}

/** [Uncaught] as the default handler while [run] runs, unless the application set its own. */
internal fun <A> uncaughtLogged(run: () -> A): A {
    if (Thread.getDefaultUncaughtExceptionHandler() != null) return run()
    val handler = Uncaught()
    Thread.setDefaultUncaughtExceptionHandler(handler)
    try {
        return run()
    } finally {
        if (Thread.getDefaultUncaughtExceptionHandler() === handler) Thread.setDefaultUncaughtExceptionHandler(null)
    }
}
