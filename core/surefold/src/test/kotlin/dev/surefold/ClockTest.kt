package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeGreaterThanOrEqual
import dev.surefold.assert.shouldBeLessThan
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class ClockTest {

    private fun failing(attempts: AtomicInteger): Nothing {
        attempts.incrementAndGet()
        error("the action nobody expects to succeed")
    }

    @Test
    fun `a backoff under a fixed clock does not wait`() {
        val schedule = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(5)
        val attempts = AtomicInteger()

        val elapsed = measureTime {
            shouldThrow<IllegalStateException> {
                clock.locally(fixedClock()) { schedule.retry { failing(attempts) } }
            }
        }

        withClue("the same schedule on the wall clock waits more than a minute") {
            elapsed shouldBeLessThan 1.seconds
        }
        attempts.get() shouldBeGreaterThanOrEqual 2
    }

    @Test
    fun `a fixed clock changes when a schedule waits, not what it decides`() {
        val schedule = Schedule.exponential<Throwable>(1.milliseconds) and Schedule.recurs(3)
        val onTheWallClock = AtomicInteger()
        val onAFixedClock = AtomicInteger()

        shouldThrow<IllegalStateException> { schedule.retry { failing(onTheWallClock) } }
        shouldThrow<IllegalStateException> {
            clock.locally(fixedClock()) { schedule.retry { failing(onAFixedClock) } }
        }

        onAFixedClock.get() shouldBe onTheWallClock.get()
    }

    @Test
    fun `the system clock reads the wall clock and waits on it`() {
        val before = Instant.now()

        val elapsed = measureTime { SystemClock.sleep(20.milliseconds) }

        SystemClock.now() shouldBeGreaterThanOrEqual before
        elapsed shouldBeGreaterThanOrEqual 20.milliseconds
    }

    @Test
    fun `a fixed clock does not move`() {
        val at = Instant.parse("2026-09-10T00:00:00Z")
        val fixed = fixedClock(at)

        fixed.sleep(1.seconds)

        fixed.now() shouldBe at
    }

    @Test
    fun `a branch waits on the clock its opener bound`() {
        val schedule = Schedule.exponential<Throwable>(1.seconds) and Schedule.recurs(3)
        val attempts = AtomicInteger()

        val elapsed = measureTime {
            clock.locally(fixedClock()) {
                parMap(listOf(1, 2)) {
                    shouldThrow<IllegalStateException> { schedule.retry { failing(attempts) } }.message
                }
            } shouldContainExactly List(2) { "the action nobody expects to succeed" }
        }

        elapsed shouldBeLessThan 1.seconds
    }
}
