package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeLessThan
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime
import kotlin.time.toJavaDuration

class BulkheadTest {

    // Two callers parked inside the bulkhead until [release] is counted down, each on a virtual thread of its own.
    private fun holdingBoth(partner: Policy, release: CountDownLatch): List<Thread> {
        val inside = CountDownLatch(2)
        val holders = (1..2).map {
            Thread.ofVirtual().start {
                partner {
                    inside.countDown()
                    release.await()
                }
            }
        }
        inside.await(PROMPT_MILLIS, TimeUnit.MILLISECONDS) shouldBe true
        return holders
    }

    @Test
    fun `a third caller is refused at once while two hold the permits`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 2)
        val partner = policy("partner") { guard(bulkhead) }
        val release = CountDownLatch(1)
        val holders = holdingBoth(partner, release)

        val refused = AtomicReference<Throwable>()
        val third = Thread.ofVirtual().start {
            try {
                partner { "ran" }
            } catch (full: Rejected.BulkheadFull) {
                refused.set(full)
            }
        }
        withClue("a bulkhead with no wait answers without waiting") {
            third.join(PROMPT_MILLIS.milliseconds.toJavaDuration()) shouldBe true
        }
        (refused.get() as Rejected.BulkheadFull).guard shouldBe "partner"

        release.countDown()
        holders.forEach { it.join() }
        bulkhead.available shouldBe 2
    }

    @Test
    fun `a caller is let in when a permit frees within its wait`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 2, maxWait = PROMPT_MILLIS.milliseconds)
        val partner = policy("partner") { guard(bulkhead) }
        val release = CountDownLatch(1)
        val holders = holdingBoth(partner, release)
        val answer = AtomicReference<String>()

        val third = Thread.ofVirtual().start { answer.set(partner { "ran" }) }
        release.countDown()
        third.join()

        answer.get() shouldBe "ran"
        holders.forEach { it.join() }
    }

    @Test
    fun `a block that throws, raises or is interrupted gives its permit back`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 1)
        val partner = policy("partner") { guard(bulkhead) }

        shouldThrow<Boom> { partner { throw Boom() } }
        bulkhead.available shouldBe 1

        attempt<Bad, Int> {
            guarded(partner, ifRejected = { Bad("refused") }) { fail(Bad("declared")) }
        } shouldBe Bad("declared").left()
        bulkhead.available shouldBe 1

        val sleeper = Sleeper()
        val interrupted = Thread.ofVirtual().start { partner { sleeper.body() } }
        sleeper.awaitStart()
        interrupted.interrupt()
        interrupted.join()
        sleeper.wasInterrupted() shouldBe true
        bulkhead.available shouldBe 1
    }

    @Test
    fun `a policy's deadline cuts the bulkhead's wait`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 2, maxWait = NEVER_FINISHES_MILLIS.milliseconds)
        val partner = policy("partner") {
            deadline(200.milliseconds)
            guard(bulkhead)
        }
        val release = CountDownLatch(1)
        val holders = holdingBoth(policy("holders") { guard(bulkhead) }, release)

        val waited = measureTime { shouldThrow<Rejected.BulkheadFull> { partner { "ran" } } }

        withClue("the wait is the deadline's, not the bulkhead's own ${NEVER_FINISHES_MILLIS}ms") {
            waited shouldBeLessThan PROMPT_MILLIS.milliseconds
        }
        release.countDown()
        holders.forEach { it.join() }
    }

    @Test
    fun `a bulkhead goes inside the breaker and outside the attempt timeout`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 1)

        shouldThrow<IllegalArgumentException> {
            policy("partner") {
                attemptTimeout(1.seconds)
                guard(bulkhead)
            }
        }.message shouldContain "deadline → retry → breaker → limiter → bulkhead → attemptTimeout"
    }

    @Test
    fun `the in-use gauge reads the permits taken, under the bulkhead's name`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 2)
        val partner = policy("partner") { guard(bulkhead) }

        capturingMetrics { measured ->
            partner { measured.gauge("surefold.bulkhead.in_use") } shouldBe 1.0
            measured.gauge("surefold.bulkhead.in_use") shouldBe 0.0
            measured.tags("surefold.bulkhead.in_use") shouldBe mapOf("name" to "partner")
        }
    }

    @Test
    fun `each call is counted as admitted or rejected under the bulkhead's name`() {
        val bulkhead = Bulkhead("partner", maxConcurrent = 1)
        val partner = policy("partner") { guard(bulkhead) }

        capturingMetrics { measured ->
            partner { 42 } shouldBe 42
            measured.counter("surefold.bulkhead.calls") shouldBe 1.0
            measured.tags("surefold.bulkhead.calls") shouldBe mapOf("name" to "partner", "outcome" to "admitted")
        }

        capturingMetrics { measured ->
            partner { shouldThrow<Rejected.BulkheadFull> { partner { 42 } } }
            measured.counter("surefold.bulkhead.calls") shouldBe 2.0
            measured.tags("surefold.bulkhead.calls") shouldBe mapOf("name" to "partner", "outcome" to "rejected")
        }
    }
}
