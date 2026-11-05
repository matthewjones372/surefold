package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactlyInAnyOrder
import dev.surefold.assert.shouldMatch
import dev.surefold.junit.samples.GappedSuite
import dev.surefold.junit.testkit.failures
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** Spec 0192's matchers fail as surefold-assert's do, carrying the actual value and naming the test's line. */
class GappedMatchersTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(GappedSuite::class.java)).execute().testEvents()
    }

    private val failures: Map<String, List<String>> by lazy {
        events.failures().mapValues { (_, thrown) -> thrown.message.orEmpty().lines().drop(1) }
    }

    @Test
    fun `every matcher passes when it holds, and each fails when it does not`() {
        events shouldHaveSucceeded listOf("passes every matcher")
        failures.keys shouldContainExactlyInAnyOrder
            listOf(
                "fails in any order",
                "fails shouldBeEmpty",
                "fails shouldNotBeEmpty on a map",
                "fails shouldNotBeNull",
                "fails shouldBeLessThan",
                "fails shouldBeGreaterThanOrEqual",
                "fails shouldContainAll",
                "fails shouldContainKey",
                "fails shouldNotContainKey",
                "fails shouldContain on a map",
                "fails shouldContainExactly",
                "fails shouldHaveSize",
                "fails shouldBeNull",
                "fails shouldBeInRange",
                "fails shouldContainAll on a map",
                "fails shouldBeRight",
                "fails shouldBeLeft",
                "fails shouldContainOnly",
                "fails shouldContainExactly on a map",
                "fails shouldStartWith",
                "fails shouldEndWith",
                "fails shouldNotBeBlank",
                "fails shouldMatch",
                "fails shouldContain a match",
            )
    }

    @Test
    fun `a failed matcher says what it wanted and what it found, at the test's line`() {
        failures.mapValues { (_, lines) -> lines.take(2) } shouldBe
            mapOf(
                "fails in any order" to listOf("expected: [1, 2, 3] in any order", "but was:  [1, 2, 2, 3]"),
                "fails shouldBeEmpty" to listOf("expected: no elements", "but was:  [1, 2, 2, 3]"),
                "fails shouldNotBeEmpty on a map" to listOf("expected: at least one entry", "but was:  {}"),
                "fails shouldNotBeNull" to listOf("expected: a value, not null", "but was:  null"),
                "fails shouldBeLessThan" to listOf("expected: less than 2", "but was:  3"),
                "fails shouldBeGreaterThanOrEqual" to listOf("expected: at least 2", "but was:  1"),
                "fails shouldContainAll" to listOf("expected: all of [3, 4]", "but was:  [1, 2, 2, 3], missing [4]"),
                "fails shouldContainKey" to listOf("expected: a map with the key b", "but was:  {a=1}"),
                "fails shouldNotContainKey" to listOf("expected: a map without the key a", "but was:  {a=1}"),
                "fails shouldContain on a map" to listOf("expected: a map with a=2", "but was:  {a=1}"),
                "fails shouldContainExactly" to listOf("expected: [1, 2, 3]", "but was:  [1, 2, 2, 3]"),
                "fails shouldHaveSize" to listOf("expected: 3 elements", "but was:  4 in [1, 2, 2, 3]"),
                "fails shouldBeNull" to listOf("expected: null", "but was:  1"),
                "fails shouldBeInRange" to listOf("expected: in 1..3", "but was:  4"),
                "fails shouldContainAll on a map" to
                    listOf("expected: all of {a=1, b=2}", "but was:  {a=1}, missing {b=2}"),
                "fails shouldBeRight" to listOf("expected: a Right", "but was:  Left(value=no)"),
                "fails shouldBeLeft" to listOf("expected: a Left", "but was:  Right(value=1)"),
                "fails shouldContainOnly" to listOf("expected: only and all of [1, 2]", "but was:  [1, 2, 2, 3]"),
                "fails shouldContainExactly on a map" to listOf("expected: {a=2}", "but was:  {a=1}"),
                "fails shouldStartWith" to
                    listOf("expected: a string starting with \"junit\"", "but was:  \"surefold-junit\""),
                "fails shouldEndWith" to
                    listOf("expected: a string ending with \"surefold\"", "but was:  \"surefold-junit\""),
                "fails shouldNotBeBlank" to listOf("expected: a string that is not blank", "but was:  \" \""),
                "fails shouldMatch" to listOf("expected: a string matching /surefold/", "but was:  \"surefold-junit\""),
                "fails shouldContain a match" to
                    listOf("expected: a string containing a match for /\\d/", "but was:  \"surefold-junit\""),
            )
        failures.values.forEach { lines -> lines[2] shouldMatch Regex("at Samples\\.kt:\\d+") }
    }

    @Test
    fun `a miss does not run a narrowing matcher's lambda`() {
        failures.keys.containsAll(listOf("fails shouldNotBeNull", "fails shouldBeRight", "fails shouldBeLeft")) shouldBe
            true
        GappedSuite.thenRan.get() shouldBe 0
    }
}
