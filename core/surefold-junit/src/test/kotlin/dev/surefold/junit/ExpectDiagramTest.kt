package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.samples.OrderSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** A failed `expect`, compiled with the power-assert plugin as this module's tests are, drawn as spec 0175 draws it. */
class ExpectDiagramTest {

    @Test
    fun `a failed expect draws each value under the expression, after the heading and before the line`() {
        val failed = EngineTestKit.engine("surefold").selectors(selectClass(OrderSuite::class.java)).execute()
            .testEvents().failed().list().single()
            .getRequiredPayload(TestExecutionResult::class.java).throwable.get()

        failed.message shouldBe """
            an order is saved once: 1 of 2 expectations failed
            expect(db.count() == 1)
                   |  |       |
                   |  2       false
                   Database(orders=2)
            at OrderSuite.kt:15
        """.trimIndent()
    }
}
