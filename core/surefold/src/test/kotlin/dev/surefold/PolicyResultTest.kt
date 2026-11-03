package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldBeLeft
import dev.surefold.assert.shouldBeRight
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.attempt
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private class Transport : RuntimeException("transport")

private class Refusal : RuntimeException("refusal")

/** Infrastructure failures count; the provider's answer about the request does not. */
private val transportOnly: (Throwable) -> Boolean = { it !is Refusal }

class PolicyResultTest {

    private fun breaker(maxFailures: Int = 2, counts: (Throwable) -> Boolean = { it !is Rejected }) =
        CircuitBreaker("partner", maxFailures, Schedule.spaced(15.seconds), counts)

    @Test
    fun `a right passes through as the same value and is a success`() {
        val breaker = breaker()
        val partner = policy("partner") { guard(breaker) }
        val value = Any()

        partner.guardResult { Either.Right(value) }.shouldBeRight() shouldBe value
        breaker.state shouldBe CircuitBreaker.State.Closed(0)
    }

    @Test
    fun `selected lefts open the breaker without a throw and come back as the same instance`() {
        val breaker = breaker()
        val partner = policy("partner") { guard(breaker) }
        val failure = Transport()

        partner.guardResult { Either.Left(failure) }.shouldBeLeft() shouldBe failure
        breaker.state shouldBe CircuitBreaker.State.Closed(1)
        partner.guardResult { Either.Left(failure) }.shouldBeLeft() shouldBe failure
        breaker.state.shouldBeInstanceOf<CircuitBreaker.State.Open>()

        val calls = AtomicInteger()
        partner.guardResult { Either.Right(calls.incrementAndGet()) }
            .shouldBeLeft().shouldBeInstanceOf<Rejected.CircuitOpen>()
        withClue("an open breaker does not make the call") { calls.get() shouldBe 0 }
    }

    @Test
    fun `an unselected left is the provider's answer and resets the failure streak`() {
        val breaker = breaker()
        val partner = policy("partner") { guard(breaker) }
        val refusal = Refusal()

        partner.guardResult(transportOnly) { Either.Left(Transport()) }
        partner.guardResult(transportOnly) { Either.Left(refusal) }.shouldBeLeft() shouldBe refusal
        breaker.state shouldBe CircuitBreaker.State.Closed(0)
        partner.guardResult(transportOnly) { Either.Left(Transport()) }
        breaker.state shouldBe CircuitBreaker.State.Closed(1)
    }

    @Test
    fun `the breaker's own predicate leaves a selected left uncounted`() {
        val breaker = breaker(counts = { it !is IllegalArgumentException })
        val partner = policy("partner") { guard(breaker) }

        partner.guardResult { Either.Left(Transport()) }
        partner.guardResult { Either.Left(IllegalArgumentException()) }
        withClue("neither a failure nor a success to the breaker") {
            breaker.state shouldBe CircuitBreaker.State.Closed(1)
        }
    }

    @Test
    fun `a half open trial follows the result it answered`() {
        val time = JumpingClock(forksWait = true)
        val ignored = IllegalArgumentException("uncounted")
        val breaker = breaker(maxFailures = 1, counts = { it !== ignored })
        val partner = policy("partner") { guard(breaker) }

        clock.locally(time) {
            partner.guardResult { Either.Left(Transport()) }
            time.sleep(15.seconds)
            partner.guardResult { Either.Left(Transport()) }
            withClue("a selected left fails the trial") {
                breaker.state shouldBe CircuitBreaker.State.Open(time.now().plusSeconds(15))
            }

            time.sleep(15.seconds)
            partner.guardResult { Either.Left(ignored) }
            withClue("a left the breaker does not count hands the trial on") {
                breaker.state shouldBe CircuitBreaker.State.Open(time.now())
            }

            partner.guardResult(transportOnly) { Either.Left(Refusal()) }
            withClue("an answer from the provider closes it") { breaker.state shouldBe CircuitBreaker.State.Closed(0) }

            partner.guardResult { Either.Left(Transport()) }
            time.sleep(15.seconds)
            partner.guardResult { Either.Right(42) }.shouldBeRight() shouldBe 42
            breaker.state shouldBe CircuitBreaker.State.Closed(0)
        }
    }

    @Test
    fun `selected lefts are retried and the last one is answered`() {
        val attempts = AtomicInteger()
        val failures = List(3) { Transport() }
        val partner = policy("partner") { retry(Schedule.recurs(2)) }

        partner.guardResult { Either.Left(failures[attempts.getAndIncrement()]) }.shouldBeLeft() shouldBe failures[2]
        attempts.get() shouldBe 3
    }

    @Test
    fun `a retry that reaches a right answers it`() {
        val attempts = AtomicInteger()
        val partner = policy("partner") { retry(Schedule.recurs(5)) }

        partner.guardResult {
            if (attempts.incrementAndGet() < 3) Either.Left(Transport()) else Either.Right("charged")
        }.shouldBeRight() shouldBe "charged"
        attempts.get() shouldBe 3
    }

    @Test
    fun `an unselected left is not retried`() {
        val attempts = AtomicInteger()
        val refusal = Refusal()
        val partner = policy("partner") { retry(Schedule.recurs(3)) }

        partner.guardResult(transportOnly) { attempts.incrementAndGet(); Either.Left(refusal) }
            .shouldBeLeft() shouldBe refusal
        attempts.get() shouldBe 1
    }

    @Test
    fun `the selector is asked once an attempt`() {
        val asked = AtomicInteger()
        val limit = Limit.adaptive("partner", initial = 100)
        val partner = policy("partner") { retry(Schedule.recurs(1)); guard(breaker(maxFailures = 5)); guard(limit) }

        partner.guardResult({ asked.incrementAndGet(); true }) { Either.Left(Transport()) }
        asked.get() shouldBe 2
    }

    @Test
    fun `retries over selected lefts stop before the deadline`() {
        val time = JumpingClock(forksWait = true)
        val attempts = AtomicInteger()
        val failure = Transport()
        val partner = policy("partner") { deadline(1.seconds); retry(Schedule.spaced(400.milliseconds)) }

        clock.locally(time) {
            partner.guardResult { attempts.incrementAndGet(); Either.Left(failure) }.shouldBeLeft() shouldBe failure
        }
        attempts.get() shouldBe 3
        time.elapsed() shouldBe 800.milliseconds
    }

    @Test
    fun `an attempt timeout is retried and answered whatever the selector says`() {
        val time = JumpingClock(forksWait = false)
        val calls = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.spaced(100.milliseconds))
            attemptTimeout(300.milliseconds)
        }

        clock.locally(time) {
            partner.guardResult({ false }) { calls.incrementAndGet(); Either.Right(Sleeper().body()) }
                .shouldBeLeft().shouldBeInstanceOf<TimeoutException>()
        }
        calls.get() shouldBe 3
        time.elapsed() shouldBe 1.seconds
    }

    @Test
    fun `what the block throws keeps the thrown rules and is answered on the left`() {
        val breaker = breaker(maxFailures = 2)
        val attempts = AtomicInteger()
        val thrown = Transport()
        val partner = policy("partner") { retry(Schedule.recurs(1)); guard(breaker) }

        partner.guardResult<Int>({ false }) { attempts.incrementAndGet(); throw thrown }.shouldBeLeft() shouldBe thrown
        withClue("retried and counted though the selector picks nothing") {
            attempts.get() shouldBe 2
            breaker.state.shouldBeInstanceOf<CircuitBreaker.State.Open>()
        }
    }

    @Test
    fun `a retry outside the breaker stops making calls once lefts open it`() {
        val calls = AtomicInteger()
        val partner = policy("partner") { retry(Schedule.recurs(5)); guard(breaker(maxFailures = 2)) }

        partner.guardResult { calls.incrementAndGet(); Either.Left(Transport()) }
            .shouldBeLeft().shouldBeInstanceOf<Rejected.CircuitOpen>()
        calls.get() shouldBe 2
    }

    @Test
    fun `a guard's refusal is answered on the left whatever the selector says and its body does not run`() {
        val refusal = Rejected.RateLimited("partner", 2.seconds)
        val calls = AtomicInteger()
        val partner = policy("partner") {
            guard(
                object : Guard {
                    override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A = throw refusal
                },
            )
        }

        partner.guardResult({ false }) { Either.Right(calls.incrementAndGet()) }.shouldBeLeft() shouldBe refusal
        calls.get() shouldBe 0
    }

    @Test
    fun `a bulkhead refusal still gives the rate token back`() {
        val limiter = RateLimiter("partner", 1, 1.seconds)
        val bulkhead = Bulkhead("partner", 1)
        val partner = policy("partner") { guard(limiter); guard(bulkhead) }

        bulkhead.acquire(ZERO) shouldBe true
        partner.guardResult { Either.Right(42) }.shouldBeLeft().shouldBeInstanceOf<Rejected.BulkheadFull>()
        limiter.tryAcquire() shouldBe true
        bulkhead.release()
    }

    @Test
    fun `a selected left spends its rate token because the call was made`() {
        val limiter = RateLimiter("partner", 1, 1.seconds)
        val partner = policy("partner") { guard(limiter) }

        partner.guardResult { Either.Left(Transport()) }
        limiter.tryAcquire() shouldBe false
    }

    @Test
    fun `only selected lefts are dropped samples to an adaptive limit`() {
        val limit = Limit.adaptive("partner", initial = 100)
        val partner = policy("partner") { guard(limit) }

        partner.guardResult(transportOnly) { Either.Left(Refusal()) }
        withClue("an answer is a measured sample") { limit.current shouldBe 100 }
        partner.guardResult(transportOnly) { Either.Left(Transport()) }
        withClue("a failure is a dropped one") { limit.current shouldBe 90 }
        limit.inFlight shouldBe 0
    }

    @Test
    fun `fatal interrupt cancellation and foreign fail values go up uncounted and unretried`() {
        val breaker = breaker(maxFailures = 1)
        val calls = AtomicInteger()
        val partner = policy("partner") { retry(Schedule.recurs(2)); guard(breaker) }

        for (stop in listOf(InterruptedException("stop"), CancellationException("stop"), LinkageError("stop"))) {
            val thrown = shouldThrow<Throwable> { partner.guardResult { calls.incrementAndGet(); Either.Left(stop) } }
            thrown shouldBe stop
        }
        attempt<String, Int> {
            partner.guardResult<Int> {
                calls.incrementAndGet()
                fail("outside")
            }
            42
        } shouldBe Either.Left("outside")

        withClue("one call each, none retried or counted") {
            calls.get() shouldBe 4
            breaker.state shouldBe CircuitBreaker.State.Closed(0)
        }
    }

    @Test
    fun `each call is counted once by what it answered`() {
        val partner = policy("partner") { retry(Schedule.recurs(2)) }

        capturingMetrics { measured ->
            partner.guardResult(transportOnly) { Either.Left(Refusal()) }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls") shouldBe
                mapOf("policy" to "partner", "outcome" to "success", "refused_by" to "none")
        }
        capturingMetrics { measured ->
            partner.guardResult { Either.Left(Transport()) }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls")["outcome"] shouldBe "failure"
        }

        val guarded = policy("partner") { guard(breaker(maxFailures = 1)) }
        guarded.guardResult { Either.Left(Transport()) }
        capturingMetrics { measured ->
            guarded.guardResult { Either.Right(42) }
            measured.tags("surefold.policy.calls") shouldBe
                mapOf("policy" to "partner", "outcome" to "rejected", "refused_by" to "breaker")
        }
    }
}
