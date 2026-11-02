package dev.surefold.junit.samples

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.flaky
import dev.surefold.junit.nonFlaky
import dev.surefold.junit.timeout
import dev.surefold.resource
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** How many times each test has run, fresh for each run of the suite. */
class Runs {
    private val counts = ConcurrentHashMap<String, AtomicInteger>()

    fun next(test: String): Int = counts.computeIfAbsent(test) { AtomicInteger() }.incrementAndGet()
}

/** Tests that pass or fail by how many times they have run, under the timing aspects. */
object TimingSuite : SharedSuite<Runs>() {
    override val shared: Resource<Runs> = resource { Runs() }

    init {
        test("passes on its third attempt", flaky(3)) { runs -> expect(runs.next("third") >= 3) }
        test("fails both of its attempts", flaky(2)) { runs -> expect(runs.next("both") >= 3) }
        test("fails one run in 10", nonFlaky(50)) { runs -> expect(runs.next("one in 10") % 10 != 7) }
        test("sleeps past its timeout", timeout(100.milliseconds)) { _ ->
            Thread.sleep(1.minutes.inWholeMilliseconds)
            expect(true)
        }
    }
}

/** Spec 0193: the build's default timeout, for a test with none of its own and not for one that has one. */
object DefaultTimeoutSuite : Suite() {
    init {
        // Long enough to pass the default the test gives, short enough not to hold up a run that selects every suite.
        test("sleeps past the default") {
            Thread.sleep(SLEEP_PAST_DEFAULT_MILLIS)
            expect(true)
        }
        test("outlasts the default under its own timeout", timeout(1.minutes)) {
            Thread.sleep(SLEEP_PAST_DEFAULT_MILLIS)
            expect(true)
        }
    }

    private const val SLEEP_PAST_DEFAULT_MILLIS = 500L
}
