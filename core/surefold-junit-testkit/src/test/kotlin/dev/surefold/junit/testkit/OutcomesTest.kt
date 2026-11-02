package dev.surefold.junit.testkit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import dev.surefold.junit.testkit.samples.OutcomesSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError

class OutcomesTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(OutcomesSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `each matcher passes on exactly the tests that ended its way, in any order`() {
        events shouldHaveSucceeded listOf("also passes", "passes")
        events.shouldHaveFailed("throws", "fails a check")
        events shouldHaveSkipped listOf("is skipped")
    }

    @Test
    fun `a miss names an expected pass that failed with its failure, and a pass that was not named`() {
        val miss = shouldThrow<AssertionFailedError> { events.shouldHaveSucceeded("passes", "fails a check") }

        miss.message shouldBe """
            expected exactly these tests to have succeeded: [fails a check, passes]
            but:
              "fails a check" failed: fails a check: 1 of 1 expectations failed
              "also passes" succeeded too, and was not named
        """.trimIndent()
        miss.expected.value shouldBe listOf("fails a check", "passes")
        miss.actual.value shouldBe listOf("also passes", "passes")
    }

    @Test
    fun `a miss says when a named test was skipped or did not run`() {
        val miss = shouldThrow<AssertionFailedError> {
            events.shouldHaveFailed("throws", "fails a check", "is skipped", "nope")
        }

        miss.message shouldContain "\"is skipped\" was skipped: not yet"
        miss.message shouldContain "\"nope\" did not run"
    }

    @Test
    fun `failureOf answers what a failed test threw`() {
        events.failureOf("throws").shouldBeInstanceOf<IllegalStateException>().message shouldBe "no database"
    }

    @Test
    fun `failureOf says how a test ended when it did not fail`() {
        shouldThrow<AssertionFailedError> { events.failureOf("passes") }.message shouldBe
            "\"passes\" did not fail: it succeeded"
        shouldThrow<AssertionFailedError> { events.failureOf("is skipped") }.message shouldBe
            "\"is skipped\" did not fail: it was skipped: not yet"
        shouldThrow<AssertionFailedError> { events.failureOf("nope") }.message.orEmpty() shouldContain
            "no test named \"nope\" ran"
    }

    @Test
    fun `failures answers what each failed test threw, by name`() {
        events.failures().mapValues { (_, thrown) -> thrown::class } shouldBe mapOf(
            "fails a check" to AssertionFailedError::class,
            "throws" to IllegalStateException::class,
        )
    }

    @Test
    fun `shouldHaveSkipped with reasons passes on exactly the tests skipped, each for its reason`() {
        events shouldHaveSkipped mapOf("is skipped" to "not yet")
        events.skipReasons() shouldBe mapOf("is skipped" to "not yet")
    }

    @Test
    fun `a miss names a test skipped for another reason, with the reason it gave`() {
        val miss = shouldThrow<AssertionFailedError> { events shouldHaveSkipped mapOf("is skipped" to "no Docker") }

        miss.message shouldBe """
            expected exactly these tests to have been skipped, for these reasons: {is skipped=no Docker}
            but:
              "is skipped" was skipped for "not yet", not "no Docker"
        """.trimIndent()
        miss.expected.value shouldBe mapOf("is skipped" to "no Docker")
        miss.actual.value shouldBe mapOf("is skipped" to "not yet")
    }

    @Test
    fun `a miss names a skip that was not named, and a named test that was not skipped`() {
        val miss = shouldThrow<AssertionFailedError> { events shouldHaveSkipped mapOf("passes" to "not yet") }

        miss.message.orEmpty() shouldContain "\"passes\" succeeded"
        miss.message.orEmpty() shouldContain "\"is skipped\" was skipped too, for \"not yet\", and was not named"
    }

    @Test
    fun `tags answers each test's tags by name, and an empty set for a test with none`() {
        events.started().tags() shouldBe mapOf(
            "passes" to setOf("fast"),
            "also passes" to emptySet(),
            "fails a check" to emptySet(),
            "throws" to emptySet(),
        )
    }

    @Test
    fun `names lists the tests in the order their events happened`() {
        events.skipped().names() shouldBe listOf("is skipped")
    }
}
