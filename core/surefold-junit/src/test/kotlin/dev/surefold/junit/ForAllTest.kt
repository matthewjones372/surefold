package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.junit.samples.ForAllSuite
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.MultipleFailuresError

/** forAll keeps every element's failures, and holds on no elements. */
class ForAllTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(ForAllSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `it holds when every element does, and on no elements`() {
        events shouldHaveSucceeded
            listOf("holds on no elements", "holds when every element does")
    }

    @Test
    fun `each element that does not hold is its own failure, under a count of every check`() {
        val thrown = events.failed().list().single().getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        val failed = thrown.shouldBeInstanceOf<MultipleFailuresError>()

        failed.message.orEmpty().lines().first() shouldBe
            "fails once for each element that does not: 2 of 4 expectations failed (2 failures)"
        failed.failures.map { it.message.orEmpty().lines().take(2) } shouldBe listOf(
            listOf("expected: less than 3", "but was:  3"),
            listOf("expected: less than 3", "but was:  4"),
        )
    }
}
