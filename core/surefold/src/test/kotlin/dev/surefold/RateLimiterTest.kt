package dev.surefold

import dev.surefold.RateLimiter.Reservation
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val CALLERS = 10

class RateLimiterTest {

    private val start: Instant = Instant.EPOCH

    private fun Reservation.waits(): Duration = (this as Reservation.Granted).wait

    @Test
    fun `burst calls pass at once, and the next waits exactly per over rate`() {
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 3, maxWait = 1.seconds)

        clock.locally(TestClock(start)) {
            repeat(3) { limiter.tryAcquire() shouldBe true }
            limiter.tryAcquire() shouldBe false
            limiter.reserve(cost = 1, maxWait = 1.seconds).waits() shouldBe 100.milliseconds
        }
    }

    @Test
    fun `a policy paces its calls by sleeping on the clock`() {
        val time = JumpingClock(forksWait = true)
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 2, maxWait = 1.seconds)
        val partner = policy("partner") { guard(limiter) }

        clock.locally(time) {
            repeat(4) { call -> partner { call } shouldBe call }
        }

        time.elapsed() shouldBe 200.milliseconds
    }

    @Test
    fun `a wait past maxWait is refused with its retryAfter, and the bucket is left as it was`() {
        val time = TestClock(start)
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 1, maxWait = 50.milliseconds)
        val partner = policy("partner") { guard(limiter) }

        clock.locally(time) {
            limiter.tryAcquire() shouldBe true
            shouldThrow<Rejected.RateLimited> { partner { 42 } }.retryAfter shouldBe 100.milliseconds

            time.adjust(100.milliseconds)
            withClue("a refused reservation took nothing, so the token that refilled is still there") {
                limiter.tryAcquire() shouldBe true
            }
        }
    }

    @Test
    fun `concurrent callers are served in the order they reserved`() {
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 1, maxWait = 1.seconds * CALLERS)
        val waits = ConcurrentLinkedQueue<Duration>()
        val go = CountDownLatch(1)

        clock.locally(TestClock(start)) {
            limiter.tryAcquire() shouldBe true
            val callers = (1..CALLERS).map {
                Thread.ofVirtual().start {
                    clock.locally(TestClock(start)) {
                        go.await()
                        waits += limiter.reserve(cost = 1, maxWait = 1.seconds * CALLERS).waits()
                    }
                }
            }
            go.countDown()
            callers.forEach { it.join() }
        }

        withClue("each reservation queues behind the one before it, so no two callers share a slot") {
            waits.sorted() shouldContainExactly (1..CALLERS).map { 100.milliseconds * it }
        }
    }

    @Test
    fun `a policy refuses at once when the wait would outrun its deadline`() {
        val time = JumpingClock(forksWait = true)
        val limiter = RateLimiter("partner", rate = 1, per = 1.seconds, burst = 1, maxWait = 10.seconds)
        val partner = policy("partner") {
            deadline(500.milliseconds)
            guard(limiter)
        }

        clock.locally(time) {
            partner { 42 } shouldBe 42
            shouldThrow<Rejected.RateLimited> { partner { 42 } }.retryAfter shouldBe 1.seconds
        }

        time.elapsed() shouldBe Duration.ZERO
    }

    @Test
    fun `a weighted call takes its cost, and a bulkhead's refusal gives it back`() {
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 3)
        val bulkhead = Bulkhead("pool", maxConcurrent = 1)
        val search = policy("search") {
            guard(limiter, cost = 3)
            guard(bulkhead)
        }
        val holder = policy("holder") { guard(bulkhead) }

        clock.locally(TestClock(start)) {
            holder {
                shouldThrow<Rejected.BulkheadFull> { search { 42 } }
            }
            withClue("the call was never made, so its three tokens are back") {
                search { 42 } shouldBe 42
            }
            limiter.tryAcquire() shouldBe false
        }
    }

    @Test
    fun `a limiter goes after the breaker and before the bulkhead`() {
        val limiter = RateLimiter("partner", rate = 1, per = 1.seconds)

        shouldThrow<IllegalArgumentException> {
            policy("partner") {
                guard(Bulkhead("pool", maxConcurrent = 1))
                guard(limiter)
            }
        }.message shouldContain "deadline → retry → breaker → limiter → bulkhead → attemptTimeout"
    }

    @Test
    fun `each call is counted by how it was let through, and each wait is recorded, under the limiter's name`() {
        val limiter = RateLimiter("partner", rate = 10, per = 1.seconds, burst = 1, maxWait = 1.seconds)

        clock.locally(TestClock(start)) {
            capturingMetrics { measured ->
                limiter.tryAcquire() shouldBe true
                measured.tags("surefold.ratelimiter.calls") shouldBe
                    mapOf("name" to "partner", "outcome" to "immediate")
            }

            capturingMetrics { measured ->
                limiter.reserve(cost = 1, maxWait = 1.seconds).waits() shouldBe 100.milliseconds
                measured.tags("surefold.ratelimiter.calls") shouldBe mapOf("name" to "partner", "outcome" to "waited")
                measured.histogram("surefold.ratelimiter.wait") shouldContainExactly listOf(0.1)
                measured.tags("surefold.ratelimiter.wait") shouldBe mapOf("name" to "partner")
            }

            capturingMetrics { measured ->
                limiter.tryAcquire() shouldBe false
                measured.counter("surefold.ratelimiter.calls") shouldBe 1.0
                measured.tags("surefold.ratelimiter.calls") shouldBe mapOf("name" to "partner", "outcome" to "rejected")
                measured.histogram("surefold.ratelimiter.wait") shouldContainExactly emptyList()
            }
        }
    }
}
