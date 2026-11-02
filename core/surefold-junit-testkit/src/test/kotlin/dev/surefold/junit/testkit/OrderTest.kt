package dev.surefold.junit.testkit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.testkit.samples.OrderSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

class OrderTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(OrderSuite::class.java)).execute()
    }

    @Test
    fun `finishOrder answers tests, then their suite, then the engine, in the order they finished, without a skip`() {
        events.allEvents().finishOrder() shouldBe listOf("first", "second", "OrderSuite", "Surefold")
    }

    @Test
    fun `finishOrder reads only the events it is given`() {
        events.testEvents().finishOrder() shouldBe listOf("first", "second")
    }
}
