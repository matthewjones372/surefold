package dev.surefold

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import java.util.ServiceLoader
import org.junit.jupiter.api.Test

/** What a number does when nobody has bound anywhere for it to go, and when somebody has. */
class MetricsTest {

    @Test
    fun `a counter reads as what it was incremented by`() {
        val counted = capturingMetrics { measured ->
            counter("petshop.adoptions").increment()
            counter("petshop.adoptions").increment(by = 2.0)
            measured.counter("petshop.adoptions")
        }

        withClue("looked up by name each time rather than held, which is the call site this is for") {
            counted shouldBe 3.0
        }
    }

    @Test
    fun `a gauge reads as what it was last set to`() {
        val depth = capturingMetrics { measured ->
            gauge("petshop.queue.depth").set(4.0)
            gauge("petshop.queue.depth").set(2.0)
            measured.gauge("petshop.queue.depth")
        }

        depth shouldBe 2.0
    }

    @Test
    fun `a histogram keeps every value it was given`() {
        val taken = capturingMetrics { measured ->
            histogram("petshop.adopt.ms").record(12.0)
            histogram("petshop.adopt.ms").record(31.0)
            measured.histogram("petshop.adopt.ms")
        }

        taken shouldBe listOf(12.0, 31.0)
    }

    @Test
    fun `nothing bound records nothing and throws nothing`() {
        withClue("a service with no adapter is not a service with a broken one") {
            counter("nowhere").increment()
            gauge("nowhere").set(1.0)
            histogram("nowhere").record(1.0)
        }
        metrics.get() shouldBe NoMetrics
    }

    @Test
    fun `nothing registered on the classpath leaves the one that records nothing`() {
        ServiceLoader.load(Metrics::class.java, Metrics::class.java.classLoader).count() shouldBe 0
        metrics.get() shouldBe NoMetrics
    }

    @Test
    fun `a binding wins over whatever the classpath had`() {
        val captured = CapturedMetrics()

        metrics.locally(captured) { metrics.get() } shouldBe captured
    }
}
