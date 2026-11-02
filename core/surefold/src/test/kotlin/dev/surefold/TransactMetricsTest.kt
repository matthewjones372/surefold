package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldThrow
import dev.surefold.fail.attempt
import org.junit.jupiter.api.Test

class TransactMetricsTest {

    // Commits a change to [shared] on a thread capturingMetrics does not reach: only the step under test is measured.
    private fun <T> changeElsewhere(shared: Shared<T>, change: (T) -> T) =
        Thread.ofVirtual().start { transact("elsewhere") { shared.update(change) } }.join()

    @Test
    fun `a step that lost once to a contending step records a run, a retry and two attempts`() {
        val stalls = Shared(10, name = "free.stalls")
        var runs = 0

        capturingMetrics { measured ->
            transact("hold") {
                val left = stalls.get()
                if (++runs == 1) changeElsewhere(stalls) { it + 1 }
                stalls.set(left - 1)
            }

            measured.counter("surefold.transact.runs") shouldBe 1.0
            measured.tags("surefold.transact.runs") shouldBe mapOf("transact" to "hold", "outcome" to "committed")
            measured.counter("surefold.transact.retries") shouldBe 1.0
            measured.tags("surefold.transact.retries") shouldBe mapOf("transact" to "hold", "cause" to "free.stalls")
            measured.histogram("surefold.transact.attempts") shouldBe listOf(2.0)
            measured.tags("surefold.transact.attempts") shouldBe mapOf("transact" to "hold")
        }
    }

    @Test
    fun `a step that commits on its first run records one run and one attempt, and no retry`() {
        val free = Shared(10)

        capturingMetrics { measured ->
            transact { free.update { it - 1 } }

            measured.counter("surefold.transact.runs") shouldBe 1.0
            measured.tags("surefold.transact.runs") shouldBe mapOf("transact" to "unnamed", "outcome" to "committed")
            measured.counter("surefold.transact.retries") shouldBe 0.0
            measured.histogram("surefold.transact.attempts") shouldBe listOf(1.0)
        }
    }

    @Test
    fun `a step that ran alone records its run as solo, once`() {
        val free = Shared(10)

        capturingMetrics { measured ->
            transact("report", soloAfter = 0) { free.update { it - 1 } }

            measured.counter("surefold.transact.runs") shouldBe 1.0
            measured.tags("surefold.transact.runs") shouldBe mapOf("transact" to "report", "outcome" to "solo")
        }
    }

    @Test
    fun `a step that throws or fails records a failed run`() {
        capturingMetrics { measured ->
            shouldThrow<IllegalStateException> { transact("hold") { error("payment refused") } }
            measured.tags("surefold.transact.runs") shouldBe mapOf("transact" to "hold", "outcome" to "failed")

            attempt<String, Unit> { transact("hold") { fail("sold out") } }
            measured.counter("surefold.transact.runs") shouldBe 2.0
            measured.histogram("surefold.transact.attempts") shouldBe emptyList()
        }
    }

    @Test
    fun `a waiting step that is interrupted records a cancelled run`() {
        val free = Shared(0, name = "free")

        val outcome = capturingMetrics { measured ->
            Thread.currentThread().interrupt()
            shouldThrow<InterruptedException> {
                transact("hold") { if (free.get() == 0) waitForChange() }
            }
            measured.tags("surefold.transact.runs")
        }

        outcome shouldBe mapOf("transact" to "hold", "outcome" to "cancelled")
    }

    @Test
    fun `an effect that throws after the commit leaves the run committed`() {
        capturingMetrics { measured ->
            shouldThrow<IllegalStateException> { transact("hold") { afterCommit { error("mailer down") } } }

            measured.tags("surefold.transact.runs") shouldBe mapOf("transact" to "hold", "outcome" to "committed")
        }
    }
}
