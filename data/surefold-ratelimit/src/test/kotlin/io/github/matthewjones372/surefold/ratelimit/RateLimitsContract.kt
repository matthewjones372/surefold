package io.github.matthewjones372.surefold.ratelimit

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** What every [RateLimits] answers, in memory and on Postgres (spec 0226). */
abstract class RateLimitsContract : Suite() {
    abstract fun limits(): RateLimits

    private val noon = Instant.parse("2026-10-26T12:00:00Z")
    private val bucket = TokenBucket(10, per = 1.seconds, burst = 20)

    init {
        test("8 threads drawing on one bucket of 10 a second, burst 20, over 5 seconds are allowed exactly 70") {
            val limits = limits()
            val time = TestClock(noon)
            val allowed = AtomicInteger()
            clock.locally(time) {
                repeat(11) {
                    val ready = CountDownLatch(8)
                    (1..8).map {
                        Thread.ofVirtual().start {
                            clock.locally(time) {
                                ready.countDown()
                                ready.await()
                                repeat(20) {
                                    val decision = limits.take("checkout:c1", bucket)
                                    if (decision is Decision.Allowed) allowed.incrementAndGet()
                                }
                            }
                        }
                    }.forEach { thread -> thread.join() }
                    time.adjust(500.milliseconds)
                }
            }
            allowed.get() shouldBe 70
        }

        test("a denied take says when its tokens will be there") {
            val limits = limits()
            clock.locally(TestClock(noon)) {
                repeat(20) { limits.take("k", bucket) }
                limits.take("k", bucket) shouldBe Decision.Denied(100.milliseconds)
                limits.take("k", bucket, cost = 5) shouldBe Decision.Denied(500.milliseconds)
            }
        }

        test("a full bucket answers its burst, saying what is left, and keys do not share a bucket") {
            val limits = limits()
            clock.locally(TestClock(noon)) {
                limits.take("a", bucket, cost = 15) shouldBe Decision.Allowed(5)
                limits.take("b", bucket, cost = 15) shouldBe Decision.Allowed(5)
                limits.take("a", bucket, cost = 6) shouldBe Decision.Denied(100.milliseconds)
            }
        }

        test("a take larger than the burst is never allowed, and a take costs at least one token") {
            val limits = limits()
            clock.locally(TestClock(noon)) { (limits.take("k", bucket, cost = 21) is Decision.Denied) shouldBe true }
            shouldThrow<IllegalArgumentException> { limits.take("k", bucket, cost = 0) }
            shouldThrow<IllegalArgumentException> { TokenBucket(0, 1.seconds) }
        }
    }
}

object InMemoryRateLimitsSuite : RateLimitsContract() {
    override fun limits(): RateLimits = InMemoryRateLimits()
}
