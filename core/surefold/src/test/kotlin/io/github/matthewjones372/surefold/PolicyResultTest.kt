package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeInstanceOf
import io.github.matthewjones372.surefold.assert.shouldBeLeft
import io.github.matthewjones372.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.Fail
import io.github.matthewjones372.surefold.fail.attempt
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PolicyResultTest {
    @Test
    fun `selected failures open the breaker without throwing and retain their identity`() {
        val breaker = CircuitBreaker("partner", 2, Schedule.spaced(15.seconds))
        val partner = policy("partner") { guard(breaker) }
        val failure = IllegalStateException("transport")
        repeat(2) { partner.guardResult { Either.Left(failure) }.shouldBeLeft() shouldBe failure }
        val calls = AtomicInteger()
        partner.guardResult { calls.incrementAndGet(); Either.Right(42) }
            .shouldBeLeft().shouldBeInstanceOf<Rejected.CircuitOpen>()
        calls.get() shouldBe 0
    }

    @Test
    fun `an unselected provider refusal resets the failure streak and is not retried`() {
        val breaker = CircuitBreaker("partner", 2, Schedule.spaced(15.seconds))
        val partner = policy("partner") { guard(breaker) }
        val transport = IllegalStateException("transport")
        val refusal = IllegalArgumentException("request")
        partner.guardResult { Either.Left(transport) }
        partner.guardResult(countsAsFailure = { it !== refusal }) { Either.Left(refusal) }
            .shouldBeLeft() shouldBe refusal
        partner.guardResult { Either.Left(transport) }
        breaker.state shouldBe CircuitBreaker.State.Closed(1)
        val calls = AtomicInteger()
        policy("retry") { retry(Schedule.recurs(3)) }
            .guardResult(countsAsFailure = { false }) { calls.incrementAndGet(); Either.Left(refusal) }
        calls.get() shouldBe 1
    }

    @Test
    fun `the breaker predicate can leave a selected failure uncounted`() {
        val ignored = IllegalArgumentException("ignored")
        val breaker = CircuitBreaker("partner", 2, Schedule.spaced(15.seconds), countsAsFailure = { it !== ignored })
        val partner = policy("partner") { guard(breaker) }
        partner.guardResult { Either.Left(IllegalStateException()) }
        partner.guardResult { Either.Left(ignored) }
        breaker.state shouldBe CircuitBreaker.State.Closed(1)
    }

    @Test
    fun `selected results follow the retry schedule and remaining deadline`() {
        val time = JumpingClock(forksWait = true)
        val attempts = AtomicInteger()
        val failure = IllegalStateException("transport")
        val partner = policy("partner") { deadline(1.seconds); retry(Schedule.spaced(400.milliseconds)) }
        clock.locally(time) {
            partner.guardResult { attempts.incrementAndGet(); Either.Left(failure) }.shouldBeLeft() shouldBe failure
        }
        attempts.get() shouldBe 3
        time.elapsed() shouldBe 800.milliseconds
    }

    @Test
    fun `attempt timeouts remain retryable and stop at the deadline`() {
        val time = JumpingClock(forksWait = false)
        val calls = AtomicInteger()
        val partner = policy("partner") {
            deadline(1.seconds)
            retry(Schedule.spaced(100.milliseconds))
            attemptTimeout(300.milliseconds)
        }
        clock.locally(time) {
            partner.guardResult { calls.incrementAndGet(); Either.Right(Sleeper().body()) }
                .shouldBeLeft().shouldBeInstanceOf<TimeoutException>()
        }
        calls.get() shouldBe 3
        time.elapsed() shouldBe 1.seconds
    }

    @Test
    fun `a custom guard refusal is a value and its body does not run`() {
        val refusal = Rejected.RateLimited("partner", 2.seconds)
        val partner = policy("partner") {
            guard(object : Guard {
                override fun <E, A> Fail<E>.guard(block: Fail<E>.() -> A): A = throw refusal
            })
        }
        partner.guardResult { Either.Right(42) }.shouldBeLeft() shouldBe refusal
    }

    @Test
    fun `a typed bulkhead refusal refunds the rate token because no dependency call began`() {
        val limiter = RateLimiter("partner", 1, 1.seconds)
        val bulkhead = Bulkhead("partner", 1)
        val partner = policy("partner") { guard(limiter); guard(bulkhead) }
        bulkhead.acquire(kotlin.time.Duration.ZERO) shouldBe true
        partner.guardResult { Either.Right(42) }.shouldBeLeft().shouldBeInstanceOf<Rejected.BulkheadFull>()
        limiter.tryAcquire() shouldBe true
        bulkhead.release()
    }

    @Test
    fun `selected results are dropped adaptive samples and release their permits`() {
        val limit = Limit.adaptive("partner", initial = 100)
        val partner = policy("partner") { guard(limit) }
        partner.guardResult { Either.Left(IllegalStateException("transport")) }
        limit.current shouldBe 90
        limit.inFlight shouldBe 0
        partner.guardResult(countsAsFailure = { false }) { Either.Left(IllegalArgumentException("request")) }
        limit.current shouldBe 90
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a half open uncounted result leaves the trial available and a known answer closes it`() {
        val time = JumpingClock(forksWait = true)
        val ignored = IllegalArgumentException("uncounted")
        val breaker = CircuitBreaker("partner", 1, Schedule.spaced(15.seconds), countsAsFailure = { it !== ignored })
        val partner = policy("partner") { guard(breaker) }
        clock.locally(time) {
            partner.guardResult { Either.Left(IllegalStateException()) }
            time.sleep(15.seconds)
            partner.guardResult { Either.Left(ignored) }
            breaker.state shouldBe CircuitBreaker.State.Open(time.now())
            partner.guardResult(countsAsFailure = { false }) { Either.Left(ignored) }
            breaker.state shouldBe CircuitBreaker.State.Closed(0)
            partner.guardResult { Either.Right(42) } shouldBe Either.Right(42)
        }
    }

    @Test
    fun `explicit fatal interruption cancellation and foreign fail values propagate uncounted`() {
        val breaker = CircuitBreaker("partner", 1, Schedule.spaced(15.seconds))
        val partner = policy("partner") { retry(Schedule.recurs(2)); guard(breaker) }
        val interrupted = InterruptedException("stop")
        shouldThrow<InterruptedException> { partner.guardResult { Either.Left(interrupted) } } shouldBe interrupted
        val cancelled = CancellationException("stop")
        shouldThrow<CancellationException> { partner.guardResult { Either.Left(cancelled) } } shouldBe cancelled
        val fatal = LinkageError("stop")
        shouldThrow<LinkageError> { partner.guardResult { Either.Left(fatal) } } shouldBe fatal
        attempt<String, Int> {
            partner.guardResult<Int> { fail("outside") }
            42
        } shouldBe Either.Left("outside")
        breaker.state shouldBe CircuitBreaker.State.Closed(0)
    }

    @Test
    fun `result calls record known answers failures and guard refusals once`() {
        val partner = policy("partner") { retry(Schedule.recurs(2)) }
        capturingMetrics { measured ->
            partner.guardResult(countsAsFailure = { false }) { Either.Left(IllegalArgumentException()) }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls")["outcome"] shouldBe "success"
        }
        capturingMetrics { measured ->
            partner.guardResult { Either.Left(IllegalStateException()) }
            measured.counter("surefold.policy.calls") shouldBe 1.0
            measured.tags("surefold.policy.calls")["outcome"] shouldBe "failure"
        }
        val breaker = CircuitBreaker("partner", 1, Schedule.spaced(15.seconds))
        val guarded = policy("partner") { guard(breaker) }
        guarded.guardResult { Either.Left(IllegalStateException()) }
        capturingMetrics { measured ->
            guarded.guardResult { Either.Right(42) }
            measured.tags("surefold.policy.calls")["refused_by"] shouldBe "breaker"
        }
    }
}
