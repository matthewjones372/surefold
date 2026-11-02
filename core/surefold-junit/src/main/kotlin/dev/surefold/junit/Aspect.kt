package dev.surefold.junit

import dev.surefold.Schedule
import dev.surefold.retry
import dev.surefold.retryOrElse
import dev.surefold.timeout
import org.junit.platform.engine.TestTag
import kotlin.time.Duration

/** Runs the test it is handed, and may publish a key and value to the test's report. */
internal typealias Around = (report: (key: String, value: String) -> Unit, test: () -> Unit) -> Unit

/**
 * Something said about how a test runs, given beside its name or to a [Suite] for each of its tests. In `a + b`,
 * `a` wraps `b`, and a suite's aspects wrap each of its tests'. Aspects are known when a test is registered, so
 * discovery reads its tags without running anything.
 */
class Aspect internal constructor(
    internal val tags: Set<TestTag> = emptySet(),
    internal val ignored: String? = null,
    internal val alone: Boolean = false,
    private val arounds: List<Around> = emptyList(),
    internal val timed: Boolean = false,
) {

    /** Both sides' tags, `alone` if either is, and ignored for the outer side's reason before the inner's. */
    operator fun plus(inner: Aspect): Aspect = Aspect(
        tags + inner.tags,
        ignored ?: inner.ignored,
        alone || inner.alone,
        arounds + inner.arounds,
        timed || inner.timed,
    )

    /** These aspects as they are if they give a timeout or there is no [fallback], else inside [fallback]'s. */
    internal fun timedOrWithin(fallback: Duration?): Aspect =
        if (fallback == null || timed) this else timeout(fallback) + this

    /** Runs [test] inside every around, the leftmost outermost, handing each [report]. */
    internal fun run(report: (key: String, value: String) -> Unit = { _, _ -> }, test: () -> Unit) =
        arounds.foldRight(test) { around, inner -> { around(report, inner) } }()

    internal companion object {
        val none = Aspect()

        /** Runs a test through [around], which is handed the test to call. */
        fun around(around: (() -> Unit) -> Unit) = reporting { _, test -> around(test) }

        /** The same, handed as well a way to publish to the test's report. */
        fun reporting(around: Around) = Aspect(arounds = listOf(around))
    }
}

/** A JUnit tag, so the platform's tag filters, Gradle's `includeTags` among them, select the test by [name]. */
fun tag(name: String): Aspect = Aspect(tags = setOf(TestTag.create(name)))

/** Reports the test skipped, for [why], without running it. */
fun ignore(why: String): Aspect = Aspect(ignored = why)

/** Nothing else in the suite runs beside the test: it waits for those registered before it, and later ones wait. */
val alone: Aspect = Aspect(alone = true)

/** Runs the test up to [attempts] times, until one passes, and reports how many it took. */
fun flaky(attempts: Int): Aspect {
    require(attempts >= 1) { "flaky needs at least one attempt, not $attempts" }
    return Aspect.reporting { report, test ->
        var taken = 0
        try {
            Schedule.recurs<Throwable>(attempts - 1L).retry {
                taken++
                test()
            }
        } finally {
            report("attempts", "$taken of $attempts")
        }
    }
}

/** Runs the test [runs] times, and fails on the first run that fails, naming it. */
fun nonFlaky(runs: Int): Aspect {
    require(runs >= 1) { "nonFlaky needs at least one run, not $runs" }
    return Aspect.around { test ->
        for (run in 1..runs) {
            // Done at the first throw, so this is a catch that, like flaky's retry, passes interrupts through.
            Schedule.recurs<Throwable>(0).retryOrElse(test) { failure, _ ->
                throw AssertionError("run $run of $runs failed: ${failure.message}", failure)
            }
        }
    }
}

/**
 * Fails the test if it runs longer than [duration], interrupting it; the failure names [duration]. A test or suite
 * that gives its own timeout is not also held to the build's default, `surefold.junit.timeout.default`.
 */
fun timeout(duration: Duration): Aspect =
    Aspect(arounds = listOf { _, test -> timeout(duration, test) }, timed = true)
