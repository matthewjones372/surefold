package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldStartWith
import dev.surefold.junit.samples.WaitSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError

/** eventually waits until a check holds, and gives up with the last try's failure. */
class WaitTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(WaitSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `a check that holds on a later try passes, at the top level, as a step, and after a throw`() {
        events shouldHaveSucceeded
            listOf(
                "holds once its check does, on the third try",
                "tries again after a throw",
                "waits as a story's step",
            )
    }

    @Test
    fun `a check that never holds fails with its own failure, saying the wait gave up`() {
        val failed = events.failureOf("gives up on a check that never holds").shouldBeInstanceOf<AssertionFailedError>()

        failed.message.orEmpty().lines()[1] shouldStartWith "gave up after "
        failed.expected.value shouldBe "Adopted"
        failed.actual.value shouldBe "Adopting"
    }

    // Spec 0205: giving up answered Expectations cast to the block's type, a ClassCastException at the call site.
    @Test
    fun `a wait over a value that gives up answers the last try's value, and the test goes on to check it`() {
        val failure = events.failureOf("gives up on a value, answering the last try's")
        val lines = failure.message.orEmpty().lines()

        lines.first() shouldBe "gives up on a value, answering the last try's: 2 of 3 expectations failed (2 failures)"
        failure.toString().contains("ClassCastException") shouldBe false
    }

    @Test
    fun `a step that gives up is a failed step, and the story goes on with its answer`() {
        events.failureOf("gives up as a story's step, and the story goes on").message.orEmpty().lines().first() shouldBe
            "gives up as a story's step, and the story goes on: 1 of 2 expectations failed"
    }
}
