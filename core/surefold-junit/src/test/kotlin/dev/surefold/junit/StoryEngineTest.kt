package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.shouldStartWith
import dev.surefold.junit.samples.StorySuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.logs
import dev.surefold.junit.testkit.shouldHaveSucceeded
import dev.surefold.test.StoryFailed
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import org.opentest4j.AssertionFailedError
import org.opentest4j.MultipleFailuresError
import java.io.ByteArrayOutputStream
import java.io.PrintStream

class StoryEngineTest {

    private fun run(): Pair<EngineExecutionResults, String> {
        val printed = ByteArrayOutputStream()
        val stdout = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        val results = try {
            EngineTestKit.engine("surefold").selectors(selectClass(StorySuite::class.java)).execute()
        } finally {
            System.setOut(stdout)
        }
        return results to printed.toString(Charsets.UTF_8)
    }

    @Test
    fun `an untitled story is titled with its test's name, and a failing story's transcript is in its logs`() {
        val logs = run().first.testEvents().logs()

        logs.getValue("fails two thens").first() shouldBe "INFO Story: fails two thens"
        val thrown = logs.getValue("throws in a step").joinToString("\n")
        thrown shouldContain "Story: throws in a step"
        thrown shouldContain "✗ Given a step that throws"
    }

    @Test
    fun `a passing story prints nothing and publishes no report entry`() {
        val (events, printed) = run()

        events.testEvents() shouldHaveSucceeded listOf("passes its story")
        events.testEvents().logs().keys shouldNotContain "passes its story"
        printed shouldNotContain "passes its story"
        printed shouldNotContain "a passing story doubles two"
    }

    @Test
    fun `two failing thens joined with and are both reported, and both are marked failed in the transcript`() {
        val (events, _) = run()

        val both = events.testEvents().failureOf("fails two thens").shouldBeInstanceOf<MultipleFailuresError>()
        both.failures.map { it.message.orEmpty().lines().first() } shouldBe listOf("expected: 3", "expected: 4")
        val transcript = events.testEvents().logs().getValue("fails two thens").joinToString("\n")
        transcript shouldContain "✓ Given two"
        transcript shouldContain "✗ Then it is three"
        transcript shouldContain "✗ Then it is four"
    }

    @Test
    fun `a story's failed then is reported alone, beside a then that passed`() {
        val (events, _) = run()

        events.testEvents().failureOf("fails one then of two").shouldBeInstanceOf<AssertionFailedError>()
        val transcript = events.testEvents().logs().getValue("fails one then of two").joinToString("\n")
        transcript shouldContain "✓ Then it is two"
        transcript shouldContain "✗ Then it is five"
    }

    @Test
    fun `a step that throws fails its test with the story`() {
        run().first.testEvents().failureOf("throws in a step").shouldBeInstanceOf<StoryFailed>()
            .cause!!.message shouldBe "no database"
    }

    @Test
    fun `a failing story is coloured on the console, and plain in its logs entry and its failure`() {
        val (events, printed) = run()

        val failed = printed.lines().single { "✗ Given a step that throws" in it }
        failed shouldStartWith "\u001B[31m\u001B[1m"
        events.testEvents().logs().values.flatten().joinToString("\n") shouldNotContain "\u001B"
        events.testEvents().failureOf("throws in a step").message.orEmpty() shouldNotContain "\u001B"
    }
}
