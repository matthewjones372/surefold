package dev.surefold.junit

import dev.surefold.assert.shouldContain
import dev.surefold.junit.samples.DefaultTimeoutSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.shouldHaveFailed
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** `surefold.junit.timeout.default` holds a test with no timeout of its own to it, as Jupiter's default does. */
class DefaultTimeoutTest {

    private fun run(default: String) =
        EngineTestKit.engine("surefold")
            .configurationParameter(DEFAULT_TIMEOUT, default)
            .selectors(selectClass(DefaultTimeoutSuite::class.java))
            .execute()
            .testEvents()

    @Test
    fun `a test with no timeout of its own fails at the default, and one with its own runs past it`() {
        val events = run("200ms")

        events shouldHaveSucceeded listOf("outlasts the default under its own timeout")
        events shouldHaveFailed listOf("sleeps past the default")
        events.failureOf("sleeps past the default").toString() shouldContain "200ms"
    }

    @Test
    fun `a default that is not a duration fails the run, naming the parameter`() {
        val engine =
            EngineTestKit.engine("surefold")
                .configurationParameter(DEFAULT_TIMEOUT, "soon")
                .selectors(selectClass(DefaultTimeoutSuite::class.java))
                .execute()
                .allEvents()

        val thrown = engine.failed().list().single().getRequiredPayload(TestExecutionResult::class.java).throwable.get()
        // The platform wraps what an engine's execute throws, naming the engine.
        thrown.cause?.message.orEmpty() shouldContain "surefold.junit.timeout.default is soon"
    }
}
