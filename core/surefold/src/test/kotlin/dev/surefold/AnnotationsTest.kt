package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.shouldHaveSize
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.seconds

class AnnotationsTest {

    @Test
    fun `an annotation set outside a parMap is on every line each branch logs`() {
        val lines = capturingLogs { logs ->
            logAnnotated("correlation_id" to "abc-123") {
                parMap(listOf(1, 2, 3)) { logInfo("branch $it") }
            }
            logs.all()
        }

        lines shouldHaveSize 3
        lines.forEach { it.annotations shouldContainExactly mapOf("correlation_id" to "abc-123") }
    }

    @Test
    fun `annotations nest, and the inner one leaves with its block`() {
        val lines = capturingLogs { logs ->
            logAnnotated("tenant" to "acme") {
                logAnnotated("attempt" to "2") { logInfo("inner") }
                logInfo("outer")
            }
            logs.all()
        }

        lines[0].annotations shouldContainExactly mapOf("tenant" to "acme", "attempt" to "2")
        lines[1].annotations shouldContainExactly mapOf("tenant" to "acme")
    }

    @Test
    fun `a span says how long it had been running when a line was written`() {
        val moving = TestClock()

        val lines = clock.locally(moving) {
            capturingLogs { logs ->
                logSpan("register") {
                    logInfo("started")
                    moving.adjust(5.seconds)
                    logInfo("finished")
                }
                logs.all()
            }
        }

        lines[0].annotations["register_ms"] shouldBe "0"
        lines[1].annotations["register_ms"] shouldBe "5000"
    }

    @Test
    fun `a span is on the lines a branch writes inside it`() {
        val lines = capturingLogs { logs ->
            logSpan("fan-out") { parMap(listOf(1, 2)) { logInfo("branch $it") } }
            logs.all()
        }

        lines shouldHaveSize 2
        lines.forEach { it.annotations.keys shouldBe setOf("fan-out_ms") }
    }

    @Test
    fun `a capture collects only what its own block logged`() {
        val outer = capturingLogs { logs ->
            logInfo("mine")
            capturingLogs { inner ->
                logInfo("theirs")
                inner.all() shouldHaveSize 1
            }
            logs.all()
        }

        outer.map { it.message } shouldBe listOf("mine")
    }
}
