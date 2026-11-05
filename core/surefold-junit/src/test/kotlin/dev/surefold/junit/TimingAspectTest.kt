package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test

class TimingAspectTest {

    /** Fails on each run that [failsOn] accepts, counting runs from 1. */
    private class Counted(private val failsOn: (Int) -> Boolean) {
        val runs = AtomicInteger()

        fun run() {
            val run = runs.incrementAndGet()
            if (failsOn(run)) throw AssertionError("failed on run $run")
        }
    }

    private fun reports(aspect: Aspect, test: () -> Unit): List<Pair<String, String>> {
        val reported = CopyOnWriteArrayList<Pair<String, String>>()
        aspect.run({ key, value -> reported += key to value }, test)
        return reported
    }

    @Test
    fun `flaky(3) passes a test that fails twice, and reports 3 attempts`() {
        val test = Counted { it <= 2 }

        reports(flaky(3), test::run) shouldBe listOf("attempts" to "3 of 3")
        test.runs.get() shouldBe 3
    }

    @Test
    fun `flaky stops at the first attempt that passes`() {
        val test = Counted { it == 1 }

        reports(flaky(5), test::run) shouldBe listOf("attempts" to "2 of 5")
        test.runs.get() shouldBe 2
    }

    @Test
    fun `flaky(2) fails a test that fails twice, with the last failure, and still reports the attempts`() {
        val test = Counted { it <= 2 }
        val reported = CopyOnWriteArrayList<Pair<String, String>>()

        val failure = shouldThrow<AssertionError> { flaky(2).run({ k, v -> reported += k to v }, test::run) }

        failure.message shouldBe "failed on run 2"
        reported.toList() shouldBe listOf("attempts" to "2 of 2")
    }

    @Test
    fun `nonFlaky(50) fails on the first failing run, names it, and keeps its failure as the cause`() {
        val test = Counted { it % 10 == 7 }

        val failure = shouldThrow<AssertionError> { nonFlaky(50).run { test.run() } }

        failure.message shouldBe "run 7 of 50 failed: failed on run 7"
        failure.cause?.message shouldBe "failed on run 7"
        test.runs.get() shouldBe 7
    }

    @Test
    fun `nonFlaky runs a passing test every time`() {
        val test = Counted { false }

        nonFlaky(50).run { test.run() }

        test.runs.get() shouldBe 50
    }

    @Test
    fun `timeout interrupts a sleeping test and fails naming the duration`() {
        val interrupted = CountDownLatch(1)

        val failure =
            shouldThrow<TimeoutException> {
                timeout(100.milliseconds).run {
                    try {
                        Thread.sleep(1.minutes.inWholeMilliseconds)
                    } catch (e: InterruptedException) {
                        interrupted.countDown()
                        throw e
                    }
                }
            }

        failure.message shouldContain "100ms"
        withClue("the sleeping test saw an interrupt") { interrupted.await(10, TimeUnit.SECONDS) shouldBe true }
    }

    @Test
    fun `timeout passes on a test's own failure unchanged`() {
        val failure = shouldThrow<AssertionError> { timeout(1.minutes).run { throw AssertionError("own") } }

        failure.message shouldBe "own"
    }

    /** Sleeps until interrupted on its first two attempts, and passes on the third. */
    private class SlowTwice {
        val attempts = AtomicInteger()

        fun run() {
            if (attempts.incrementAndGet() <= 2) Thread.sleep(1.minutes.inWholeMilliseconds)
        }
    }

    @Test
    fun `timeout outside flaky limits all the attempts together`() {
        val test = SlowTwice()

        shouldThrow<TimeoutException> { (timeout(500.milliseconds) + flaky(3)).run { test.run() } }

        withClue("an interrupt is not retried") { test.attempts.get() shouldBe 1 }
    }

    @Test
    fun `flaky outside timeout limits each attempt`() {
        val test = SlowTwice()

        reports(flaky(3) + timeout(500.milliseconds), test::run) shouldBe listOf("attempts" to "3 of 3")
        test.attempts.get() shouldBe 3
    }

    @Test
    fun `flaky and nonFlaky need at least one run`() {
        shouldThrow<IllegalArgumentException> { flaky(0) }
        shouldThrow<IllegalArgumentException> { nonFlaky(0) }
    }
}
