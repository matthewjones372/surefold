package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeGreaterThanOrEqual
import dev.surefold.assert.shouldBeLessThan
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test

/** A tag bound for a block, and what a fork underneath it measures. */
class MetricTagsTest {

    @Test
    fun `a tag bound for a block is on what the block measures`() {
        val tags = capturingMetrics { measured ->
            metricTagged("species" to "tortoise") { counter("adoptions").increment() }
            measured.tags("adoptions")
        }

        tags shouldBe mapOf("species" to "tortoise")
    }

    @Test
    fun `a measurement outside the block carries nothing`() {
        val tags = capturingMetrics { measured ->
            metricTagged("species" to "tortoise") { counter("adoptions").increment() }
            counter("arrivals").increment()
            measured.tags("arrivals")
        }

        withClue("a scope that leaked would put one request's tag on the next one's numbers") {
            tags shouldBe emptyMap()
        }
    }

    @Test
    fun `a fork measures under the tag its opener bound`() {
        val tags = capturingMetrics { measured ->
            metricTagged("species" to "tortoise") {
                parMap(listOf(1, 2)) { counter("adoptions").increment() }
            }
            measured.tags("adoptions")
        }

        withClue("the reason this is in surefold rather than left to the backend") {
            tags shouldBe mapOf("species" to "tortoise")
        }
    }

    @Test
    fun `a tag at the call site joins the ones in scope`() {
        val tags = capturingMetrics { measured ->
            metricTagged("species" to "tortoise") {
                counter("adoptions", "outcome" to "taken").increment()
            }
            measured.tags("adoptions")
        }

        tags shouldBe mapOf("species" to "tortoise", "outcome" to "taken")
    }

    @Test
    fun `timed records how long the block took and answers what it returned`() {
        val (took, answered) =
            capturingMetrics { measured ->
                val answer =
                    timed("adopt") {
                        Thread.sleep(5)
                        "Nibbles"
                    }
                measured.histogram("adopt") to answer
            }

        answered shouldBe "Nibbles"
        took.size shouldBe 1
        withClue("seconds, which is the unit a backend assumes; the block slept for five ms of them") {
            took.single() shouldBeGreaterThanOrEqual 0.005
        }
        withClue("a sleep of five milliseconds recorded as five would be a thousand-fold lie") {
            took.single() shouldBeLessThan 1.0
        }
    }
}
