package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldContainExactly
import dev.surefold.fail.Either
import dev.surefold.junit.samples.MatchingSuite
import dev.surefold.junit.testkit.failures
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError

/** The value-returning matchers fail as surefold-assert's throwing ones do, and combine as `expect` does. */
class MatchersTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(MatchingSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, Throwable> by lazy { events.failures() }

    @Test
    fun `matchers pass and fail as their names say, alone and combined`() {
        events shouldHaveSucceeded
            listOf(
                "passes shouldBe and shouldNotBe",
                "passes either matcher",
                "passes shouldBeRight and shouldBeLeft",
            )
        failures.mapValues { (_, thrown) -> thrown::class } shouldContainExactly
            mapOf(
                "fails shouldBe" to AssertionFailedError::class,
                "fails shouldNotBe" to AssertionFailedError::class,
                "fails both matchers" to MultipleFailuresError::class,
                "fails shouldBeRight" to AssertionFailedError::class,
            )
    }

    @Test
    fun `a failed shouldBe carries expected and actual, so the IDE opens its diff`() {
        val failed = failures.getValue("fails shouldBe").shouldBeInstanceOf<AssertionFailedError>()

        failed.expected.value shouldBe 3
        failed.actual.value shouldBe 2
        failed.message.orEmpty().lines() shouldBe
            listOf(
                "fails shouldBe: 1 of 1 expectations failed",
                "expected: 3",
                "but was:  2",
                "at Samples.kt:${failed.stackTrace.first().lineNumber}",
                "    test(\"fails shouldBe\") { two -> two shouldBe 3 }",
            )
    }

    @Test
    fun `a failed shouldNotBe names the value it should not have been`() {
        failures.getValue("fails shouldNotBe").message.orEmpty().lines().take(3) shouldBe
            listOf(
                "fails shouldNotBe: 1 of 1 expectations failed",
                "expected: anything but 2",
                "but was:  2",
            )
    }

    @Test
    fun `two failed matchers joined by and are both reported, under a count of the checks`() {
        val failed = failures.getValue("fails both matchers").shouldBeInstanceOf<MultipleFailuresError>()

        failed.message.orEmpty().lines().first() shouldBe "fails both matchers: 2 of 2 expectations failed (2 failures)"
        failed.failures.map { it.message.orEmpty().lines().first() } shouldBe
            listOf("expected: 3", "expected: anything but 2")
    }

    @Test
    fun `a failed shouldBeRight carries the Right it wanted and the Either it found`() {
        val failed = failures.getValue("fails shouldBeRight").shouldBeInstanceOf<AssertionFailedError>()

        // opentest4j keeps only a serializable value, and an Either is not; its text is what the diff shows.
        failed.expected.stringRepresentation shouldBe Either.Right(2).toString()
        failed.actual.stringRepresentation shouldBe Either.Left(2).toString()
    }
}
