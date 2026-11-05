package dev.surefold

import dev.surefold.CircuitBreaker.State
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.Fail
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

private const val CALLERS = 10

class CircuitBreakerTest {

    private val start: Instant = Instant.EPOCH

    private fun failingTwice(breaker: CircuitBreaker): Policy {
        val partner = policy("partner") { guard(breaker) }
        repeat(2) { shouldThrow<Boom> { partner { throw Boom() } } }
        return partner
    }

    @Test
    fun `closed, open, half-open and closed again`() {
        val time = TestClock(start)
        val breaker = CircuitBreaker("partner", maxFailures = 2, resetAfter = Schedule.spaced(1.seconds))
        val ran = AtomicInteger()

        clock.locally(time) {
            val partner = failingTwice(breaker)
            breaker.state shouldBe State.Open(start.plusSeconds(1))

            val refused = shouldThrow<Rejected.CircuitOpen> { partner { ran.incrementAndGet() } }
            refused.retryAt shouldBe start.plusSeconds(1)
            withClue("an open breaker refuses without running the call") { ran.get() shouldBe 0 }

            time.adjust(1.seconds)
            partner { ran.incrementAndGet() } shouldBe 1
            breaker.state shouldBe State.Closed(0)
        }
    }

    @Test
    fun `a failed trial reopens on the schedule's next step, and its last step holds once it is done`() {
        val time = TestClock(start)
        val resetAfter = Schedule.exponential<Unit>(1.seconds) zipLeft Schedule.recurs(2)
        val breaker = CircuitBreaker("partner", maxFailures = 2, resetAfter = resetAfter)

        clock.locally(time) {
            val partner = failingTwice(breaker)

            time.adjust(1.seconds)
            shouldThrow<Boom> { partner { throw Boom() } }
            breaker.state shouldBe State.Open(start.plusSeconds(3))

            time.adjust(2.seconds)
            shouldThrow<Boom> { partner { throw Boom() } }
            breaker.state shouldBe State.Open(start.plusSeconds(5))
        }
    }

    @Test
    fun `half-open lets exactly one trial through under concurrent callers`() {
        val time = TestClock(start)
        val breaker = CircuitBreaker("partner", maxFailures = 2, resetAfter = Schedule.spaced(1.seconds))
        val partner = clock.locally(time) { failingTwice(breaker) }
        time.adjust(1.seconds)

        val go = CountDownLatch(1)
        val release = CountDownLatch(1)
        val refused = CountDownLatch(CALLERS - 1)
        val admitted = AtomicInteger()
        val callers =
            (1..CALLERS).map {
                Thread.ofVirtual().start {
                    clock.locally(time) {
                        go.await()
                        try {
                            partner {
                                admitted.incrementAndGet()
                                release.await()
                            }
                        } catch (open: Rejected.CircuitOpen) {
                            refused.countDown()
                        }
                    }
                }
            }

        go.countDown()
        withClue("every caller but the trial is refused while it is in flight") {
            refused.await(PROMPT_MILLIS, TimeUnit.MILLISECONDS) shouldBe true
        }
        admitted.get() shouldBe 1
        breaker.state shouldBe State.HalfOpen

        release.countDown()
        callers.forEach { it.join() }
        breaker.state shouldBe State.Closed(0)
    }

    @Test
    fun `a refusal from a guard inside the breaker is not counted`() {
        val breaker = CircuitBreaker("partner", maxFailures = 2, resetAfter = Schedule.spaced(1.seconds))
        val full =
            object : Guard {
                override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A = throw Rejected.BulkheadFull("pool")
            }
        val partner =
            policy("partner") {
                guard(breaker)
                guard(full)
            }

        repeat(5) { shouldThrow<Rejected.BulkheadFull> { partner { 42 } } }

        breaker.state shouldBe State.Closed(0)
    }

    @Test
    fun `a policy waits for half-open only when it falls within the deadline`() {
        val time = JumpingClock(forksWait = true)
        val breaker = CircuitBreaker("partner", maxFailures = 1, resetAfter = Schedule.spaced(500.milliseconds))
        val patient =
            policy("patient") {
                deadline(1.seconds)
                guard(breaker)
            }
        val hurried =
            policy("hurried") {
                deadline(300.milliseconds)
                guard(breaker)
            }

        clock.locally(time) {
            shouldThrow<Boom> { patient { throw Boom() } }
            patient { 42 } shouldBe 42
            time.elapsed() shouldBe 500.milliseconds

            shouldThrow<Boom> { patient { throw Boom() } }
            shouldThrow<Rejected.CircuitOpen> { hurried { 42 } }
            withClue("a wait the deadline could not cover is refused at once") {
                time.elapsed() shouldBe 500.milliseconds
            }
        }
    }

    @Test
    fun `without a deadline an open breaker refuses at once`() {
        val breaker = CircuitBreaker("partner", maxFailures = 1, resetAfter = Schedule.spaced(1.seconds))
        val partner = policy("partner") { guard(breaker) }

        clock.locally(fixedClock()) {
            shouldThrow<Boom> { partner { throw Boom() } }
            shouldThrow<Rejected.CircuitOpen> { partner { 42 } }
        }
    }

    @Test
    fun `the state gauge reads closed, open and half-open under the breaker's name`() {
        val time = TestClock(start)
        val breaker = CircuitBreaker("partner", maxFailures = 2, resetAfter = Schedule.spaced(1.seconds))

        capturingMetrics { measured ->
            clock.locally(time) {
                val partner = failingTwice(breaker)
                measured.gauge("surefold.breaker.state") shouldBe 2.0
                measured.tags("surefold.breaker.state") shouldBe mapOf("name" to "partner")

                time.adjust(1.seconds)
                partner { measured.gauge("surefold.breaker.state") } shouldBe 1.0
                measured.gauge("surefold.breaker.state") shouldBe 0.0
            }
        }
    }

    @Test
    fun `each call is counted by its outcome under the breaker's name`() {
        val breaker = CircuitBreaker("partner", maxFailures = 1, resetAfter = Schedule.spaced(1.seconds))
        val partner = policy("partner") { guard(breaker) }
        val calls =
            listOf<Pair<String, () -> Unit>>(
                "success" to { partner { 42 } shouldBe 42 },
                "failure" to { shouldThrow<Boom> { partner { throw Boom() } } },
                "rejected" to { shouldThrow<Rejected.CircuitOpen> { partner { 42 } } },
            )

        clock.locally(fixedClock()) {
            calls.forEach { (outcome, call) ->
                capturingMetrics { measured ->
                    call()
                    measured.counter("surefold.breaker.calls") shouldBe 1.0
                    measured.tags("surefold.breaker.calls") shouldBe mapOf("name" to "partner", "outcome" to outcome)
                }
            }
        }
    }
}
