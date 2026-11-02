package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldContainExactlyInAnyOrder
import dev.surefold.assert.shouldThrow
import dev.surefold.junit.samples.EachSuite
import dev.surefold.junit.samples.SharedEachSuite
import dev.surefold.junit.testkit.names
import dev.surefold.junit.testkit.shouldHaveFailed
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.DiscoverySelector
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.TestTag
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.engine.discovery.DiscoverySelectors.selectUniqueId
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import kotlin.jvm.optionals.getOrNull

/** testEach registers a test for each case, under a container named for the group (spec 0244). */
class TestEachTest {

    private val suite = "[engine:surefold]/[suite:${EachSuite::class.java.name}]"

    private fun run(selector: DiscoverySelector): EngineExecutionResults =
        EngineTestKit.engine("surefold").selectors(selector).execute()

    @Test
    fun `each case is a test of its own, and only the wrong one fails`() {
        val tests = run(selectClass(EachSuite::class.java)).testEvents()

        tests shouldHaveSucceeded listOf("a plain test", "1s", "2m")
        tests shouldHaveFailed listOf("90m")
    }

    @Test
    fun `the cases are reported under a container named for the group, inside the suite`() {
        val started = run(selectClass(EachSuite::class.java)).testEvents().started().list()

        started.associate { it.testDescriptor.displayName to it.testDescriptor.parentDisplayName() } shouldBe mapOf(
            "a plain test" to "EachSuite",
            "1s" to "a duration parses",
            "2m" to "a duration parses",
            "90m" to "a duration parses",
        )
    }

    @Test
    fun `a case's unique ID names its suite, its group and itself`() {
        val ids = run(selectClass(EachSuite::class.java)).testEvents().started().list()
            .map { it.testDescriptor.uniqueId.toString() }

        ids shouldContain "$suite/[group:a duration parses]/[test:2m]"
        ids shouldContain "$suite/[test:a plain test]"
    }

    @Test
    fun `a case selected by its unique ID runs alone, as an IDE reruns one`() {
        run(selectUniqueId("$suite/[group:a duration parses]/[test:2m]")).testEvents().started().names() shouldBe
            listOf("2m")
    }

    @Test
    fun `a group selected by its unique ID runs every case in it, and nothing else`() {
        val started = run(selectUniqueId("$suite/[group:a duration parses]")).testEvents().started().names()

        started shouldContainExactlyInAnyOrder listOf("1s", "2m", "90m")
    }

    @Test
    fun `a plain test selected by its unique ID still runs alone`() {
        run(selectUniqueId("$suite/[test:a plain test]")).testEvents().started().names() shouldBe
            listOf("a plain test")
    }

    @Test
    fun `a group with no cases is an empty container that passes`() {
        val empty = run(selectClass(EachSuite::class.java)).containerEvents().finished().list()
            .single { it.testDescriptor.displayName == "nothing to check" }

        empty.getRequiredPayload(TestExecutionResult::class.java).status shouldBe TestExecutionResult.Status.SUCCESSFUL
        empty.testDescriptor.children.size shouldBe 0
    }

    @Test
    fun `every container a case is under finishes after it`() {
        val finished = run(selectClass(EachSuite::class.java)).allEvents().finished().names()

        listOf("1s", "2m", "90m").all { finished.indexOf(it) < finished.indexOf("a duration parses") } shouldBe true
        finished.takeLast(2) shouldBe listOf("EachSuite", "Surefold")
    }

    @Test
    fun `the group's aspects apply to every case`() {
        val tagged = run(selectClass(EachSuite::class.java)).testEvents().started().list()
            .filter { TestTag.create("parsing") in it.testDescriptor.tags }.map { it.testDescriptor.displayName }

        tagged shouldContainExactlyInAnyOrder listOf("1s", "2m", "90m")
    }

    @Test
    fun `in a shared suite each case is handed the resource and its case`() {
        SharedEachSuite.seen.clear()

        run(selectClass(SharedEachSuite::class.java)).testEvents() shouldHaveSucceeded listOf("Nibbles", "Mrs Peel")

        SharedEachSuite.seen shouldContainExactlyInAnyOrder listOf("db Nibbles", "db Mrs Peel")
    }

    @Test
    fun `two cases with one name are refused, naming the group and the name`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite() {
                init {
                    testEach("a number is positive", listOf(1, 1)) { expect(it > 0) }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"a number is positive\""
        refused.message.orEmpty() shouldContain "\"1\""
    }

    @Test
    fun `two groups with one name are refused, because the name is the group's ID`() {
        val refused = shouldThrow<IllegalArgumentException> {
            object : Suite() {
                init {
                    testEach("twice", listOf(1)) { expect(it > 0) }
                    testEach("twice", listOf(2)) { expect(it > 0) }
                }
            }
        }

        refused.message.orEmpty() shouldContain "\"twice\""
    }

    private fun org.junit.platform.engine.TestDescriptor.parentDisplayName(): String? =
        parent.getOrNull()?.displayName
}
