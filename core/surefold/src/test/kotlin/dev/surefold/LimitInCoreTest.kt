package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import dev.surefold.fail.attempt
import dev.surefold.fail.left
import org.junit.jupiter.api.Test
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** A [Limit] in plain code (spec 0171): `parMap` and a policy draw on it as a stream's `mapPar` does. */
class LimitInCoreTest {

    @Test
    fun `parMap under a limit answers in order and never has more than the limit inside`() {
        val limit = Limit.fixed(3, name = "orders-db")
        val now = AtomicInteger()
        val most = AtomicInteger()

        val answers = parMap(1..50, limit) { n ->
            most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
            Thread.sleep(1)
            now.decrementAndGet()
            n * 2
        }

        answers shouldBe (1..50).map { it * 2 }
        withClue("the most calls ever inside at once") { (most.get() <= 3) shouldBe true }
        limit.inFlight shouldBe 0
    }

    @Test
    fun `parMap under a limit starts no more forks than the limit's max, however long the list`() {
        val limit = Limit.adaptive("orders-db", initial = 2, max = 4)
        val alive = AtomicInteger()
        val most = AtomicInteger()
        // Counts forks alive on it, a fork waiting for a permit as much as one holding one.
        val counting = Executor { task ->
            Thread.ofVirtual().start {
                most.accumulateAndGet(alive.incrementAndGet(), ::maxOf)
                try {
                    task.run()
                } finally {
                    alive.decrementAndGet()
                }
            }
        }

        parMap(counting, 1..100, limit) { Thread.sleep(1) }

        // A fork gives its room back just before its thread ends, so the next may start a moment before that.
        withClue("forks alive at once: at most max, and a few unwinding, not one per element") {
            (most.get() <= 2 * 4) shouldBe true
        }
    }

    @Test
    fun `parMap under a limit starts nothing after a call has failed`() {
        val calls = AtomicInteger()

        val thrown = shouldThrow<IllegalStateException> {
            parMap(1..100, Limit.fixed(1)) { n ->
                calls.incrementAndGet()
                check(n != 2) { "row $n is corrupt" }
            }
        }

        thrown.message shouldBe "row 2 is corrupt"
        withClue("one call at a time: the first, then the one that failed") { calls.get() shouldBe 2 }
    }

    @Test
    fun `a fail inside parMap under a limit is the answer, and the limit is left empty`() {
        val limit = Limit.fixed(2)

        val answered = attempt<String, List<Int>> {
            parMap(listOf(1, 2, 3), limit) { n -> if (n == 2) fail("missing $n") else n }
        }

        answered shouldBe "missing 2".left()
        limit.inFlight shouldBe 0
    }

    @Test
    fun `parMap under a limit publishes what the limit allows`() {
        capturingMetrics { captured ->
            parMap(1..10, Limit.adaptive("orders-db", initial = 5, max = 50)) { it }

            captured.gauge("surefold.limit.current") shouldBe 5.0
            captured.gauge("surefold.limit.in_use") shouldBe 0.0
        }
    }

    @Test
    fun `a policy guarded by a full limit refuses a call at once, under the limit's name`() {
        val limit = Limit.fixed(1, name = "partner")
        val partner = policy("partner") { guard(limit) }
        val inside = CountDownLatch(1)
        val leave = CountDownLatch(1)
        val holder = Thread.ofVirtual().start { partner { inside.countDown(); leave.await() } }
        inside.await()

        val refused = AtomicReference<Throwable>()
        val second = Thread.ofVirtual().start {
            try {
                partner { "ran" }
            } catch (full: Rejected.BulkheadFull) {
                refused.set(full)
            }
        }
        withClue("a limit with no wait answers without waiting") {
            second.join(Duration.ofMillis(PROMPT_MILLIS)) shouldBe true
        }
        (refused.get() as Rejected.BulkheadFull).guard shouldBe "partner"

        leave.countDown()
        holder.join()
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a policy guarded by a limit lets a call in when room frees within its wait`() {
        val limit = Limit.fixed(1)
        val partner = policy("partner") { guard(limit, maxWait = 5.seconds) }
        val inside = CountDownLatch(1)
        val holder = Thread.ofVirtual().start { partner { inside.countDown(); Thread.sleep(HOLD_MILLIS) } }
        inside.await()

        partner { "ran" } shouldBe "ran"
        holder.join()
    }

    @Test
    fun `a call through a policy's limit is one an adaptive limit learns from`() {
        val limit = Limit.adaptive("partner", initial = 100, max = 1_000)
        val partner = policy("partner") { guard(limit, maxWait = 100.milliseconds) }

        shouldThrow<IllegalStateException> { partner { error("connection reset") } }

        withClue("a failed call backs the limit off") { limit.current shouldBe 90 }
        limit.inFlight shouldBe 0
    }

    @Test
    fun `a limit takes a bulkhead's place in a policy, so the two together are refused`() {
        val thrown = shouldThrow<IllegalArgumentException> {
            policy("partner") {
                guard(Bulkhead("partner", 2))
                guard(Limit.fixed(2))
            }
        }

        thrown.message shouldContain "bulkhead appears twice"
    }

    private companion object {
        const val PROMPT_MILLIS = 2_000L
        const val HOLD_MILLIS = 100L
    }
}
