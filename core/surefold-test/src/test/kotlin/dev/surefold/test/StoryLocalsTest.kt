package dev.surefold.test

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.shouldStartWith
import dev.surefold.assert.shouldThrow
import dev.surefold.scope
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(StorySeam::class)
class StoryLocalsTest {

    private fun printedBy(block: () -> Unit): String {
        val printed = ByteArrayOutputStream()
        val console = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        try {
            block()
        } finally {
            System.setOut(console)
        }
        return printed.toString(Charsets.UTF_8)
    }

    @Test
    fun `an untitled story takes the bound title and tells its transcript where it is bound`() {
        val told = CopyOnWriteArrayList<String>()

        val printed = printedBy {
            storyTitle.locally("a bound title") {
                storyTranscript.locally({ told += it.plain }) {
                    story { Given("a step") { } }
                }
            }
        }

        told.single().lines().first() shouldBe "Story: a bound title"
        printed shouldNotContain "a bound title"
    }

    @Test
    fun `a failing story tells its transcript where it is bound before it throws`() {
        val told = CopyOnWriteArrayList<String>()

        val failed = storyTranscript.locally({ told += it.plain }) {
            shouldThrow<StoryFailed> { story("failing") { Then("it fails") { error("no") } } }
        }

        told.single() shouldBe failed.message
    }

    @Test
    fun `a title given to the story wins over the bound one`() {
        val told = CopyOnWriteArrayList<String>()

        storyTitle.locally("the bound title") {
            storyTranscript.locally({ told += it.plain }) { story("its own title") { } }
        }

        told.single() shouldBe "Story: its own title"
    }

    @Test
    fun `a story told in a fork sees what its opener bound`() {
        val told = CopyOnWriteArrayList<String>()

        storyTitle.locally("from the opener") {
            storyTranscript.locally({ told += it.plain }) {
                scope { async { story { } } }
            }
        }

        told.single() shouldBe "Story: from the opener"
    }

    @Test
    fun `a step whose value is a failure is marked failed and tells it, and the story goes on`() {
        val told = CopyOnWriteArrayList<String>()
        val wrong = AssertionError("expected: 3")

        val answered = storyTranscript.locally({ told += it.plain }) {
            tellStory("judged", failed = { (it as? String)?.let { _ -> wrong } }) {
                Then("it is three") { "two" }
                And("it goes on") { 4 }
            }
        }

        answered shouldBe 4
        val lines = told.single().lines()
        lines[1] shouldContain "✗ Then it is three"
        lines[2].trim() shouldBe "expected: 3"
        lines.last() shouldContain "✓ And it goes on"
    }

    @Test
    fun `a failure found in a nested step's value is told once, under the innermost step`() {
        val told = CopyOnWriteArrayList<String>()
        val wrong = AssertionError("expected: 3")

        storyTranscript.locally({ told += it.plain }) {
            tellStory("nested", failed = { (it as? String)?.let { _ -> wrong } }) {
                Then("the outer") { And("the inner") { "two" } }
            }
        }

        val lines = told.single().lines()
        lines[1] shouldContain "✗ Then the outer"
        lines[2] shouldContain "✗ And the inner"
        lines.count { "expected: 3" in it } shouldBe 1
    }

    @Test
    fun `a transcript's coloured copy is coloured only when colour is on, and its plain copy never is`() {
        val story = Story { (it as? String)?.let { _ -> AssertionError("expected: 3") } }
            .apply { Then("it is three") { "two" } }

        val on = story.transcript("coloured", colour = true)
        val off = story.transcript("coloured", colour = false)

        on.coloured.lines()[1] shouldStartWith "\u001B[31m\u001B[1m"
        on.plain shouldNotContain "\u001B"
        off.coloured shouldBe off.plain
        off.plain shouldBe on.plain
    }
}
