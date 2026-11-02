package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldContain
import dev.surefold.junit.samples.HoldsSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/** holds turns a check that throws into one expectation, and leaves anything else thrown alone. */
class HoldsTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(HoldsSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `a block that returns holds`() {
        events shouldHaveSucceeded listOf("holds when the block returns")
    }

    @Test
    fun `each block that throws an assertion is its own failure, naming the line that called holds`() {
        val failed = events.failureOf("fails once for each block that throws an assertion")
            .shouldBeInstanceOf<MultipleFailuresError>()

        failed.message.orEmpty().lines().first() shouldBe
            "fails once for each block that throws an assertion: 2 of 3 expectations failed (2 failures)"
        failed.failures.map { it.message.orEmpty().lines().take(2) } shouldBe listOf(
            listOf("the golden file differs", "at HoldsSamples.kt:12"),
            listOf("the stub file differs", "at HoldsSamples.kt:14"),
        )
        failed.failures.map { it.cause?.message } shouldBe listOf("the golden file differs", "the stub file differs")
    }

    @Test
    fun `an assertion's expected and found are kept`() {
        val failed =
            events.failureOf("keeps what an assertion expected and found").shouldBeInstanceOf<AssertionFailedError>()

        failed.expected.value shouldBe "Start load"
        failed.actual.value shouldBe "Stop load"
        failed.message.orEmpty() shouldContain "the tile's text"
    }

    @Test
    fun `anything else thrown errors the test with what was thrown`() {
        val thrown = events.failureOf("errors on anything else thrown").shouldBeInstanceOf<IllegalStateException>()

        thrown.message shouldBe "the page never loaded"
    }
}
