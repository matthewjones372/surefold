package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import org.junit.jupiter.api.Test

/** Spec 0109: what was written through surefold reads back through surefold. */
class ReadingsTest {

    @Test
    fun `a counter, a gauge and a histogram read back as a total, a value and a distribution`() {
        capturingMetrics {
            counter("petshop.adoptions", "species" to "tortoise").increment(by = 3.0)
            gauge("petshop.kennels").set(12.0)
            histogram("petshop.wait").record(2.0)
            histogram("petshop.wait").record(4.0)

            readings("petshop.adoptions") shouldBe
                listOf(Reading.Total("petshop.adoptions", mapOf("species" to "tortoise"), 3.0))
            readings("petshop.kennels") shouldBe listOf(Reading.Value("petshop.kennels", emptyMap(), 12.0))
            readings("petshop.wait") shouldBe
                listOf(Reading.Distribution("petshop.wait", emptyMap(), 2, 6.0, emptyMap()))
        }
    }

    @Test
    fun `a name nothing wrote, and a backend that keeps nothing, read as nothing`() {
        capturingMetrics { readings("petshop.never").shouldBeEmpty() }
        metrics.locally(NoMetrics) {
            counter("petshop.adoptions").increment()
            readings("petshop.adoptions").shouldBeEmpty()
        }
    }
}
