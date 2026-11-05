package dev.surefold.test

import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeInstanceOf
import dev.surefold.assert.shouldBeSameInstanceAs
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.shouldStartWith
import dev.surefold.assert.shouldThrow
import dev.surefold.clock
import dev.surefold.scope
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import org.junit.jupiter.api.Test

class StoryTest {

    @Test
    fun `a value one step answers is what the next step is given`() = story {
        val pets = Given("a shop with three pets") { listOf("Nibbles", "Barnaby", "Mrs Peel") }
        val taken = When("Ada adopts the first") { pets.first() }
        Then("Ada has Nibbles") { taken shouldBe "Nibbles" }
    }

    @Test
    fun `a story failing at its third step says the first two passed and what the third said`() {
        val failed =
            shouldThrow<StoryFailed> {
                story("adopting") {
                    Given("a shop") {}
                    When("Ada adopts Nibbles") {}
                    Then("Nibbles is hers") { "Bea" shouldBe "Ada" }
                    And("never reached") {}
                }
            }

        val lines = failed.message!!.lines()
        lines[0] shouldBe "Story: adopting"
        lines[1] shouldContain "✓ Given a shop"
        lines[2] shouldContain "✓ When Ada adopts Nibbles"
        lines[3] shouldContain "✗ Then Nibbles is hers"
        lines[4] shouldContain "expected: Ada"
        lines[5] shouldContain "but was:  Bea"
        failed.message!! shouldNotContain "never reached"
        failed.cause!!.message shouldStartWith "expected: Ada\nbut was:  Bea"
    }

    @Test
    fun `a failure is told once, under the innermost step that saw it`() {
        val failed =
            shouldThrow<StoryFailed> {
                story("nested") {
                    Given("the service") { And("its database") { error("refused") } }
                }
            }

        val lines = failed.message!!.lines()
        lines[1] shouldContain "✗ Given the service"
        lines[2] shouldContain "    ✗ And its database"
        lines.count { "refused" in it } shouldBe 1
    }

    @Test
    fun `but is a step too`() = story {
        But("it answers like any other") { 1 } shouldBe 1
    }

    @Test
    fun `a waiting step answers once its block stops throwing, and says how many tries it took`() {
        val asked = AtomicInteger()
        val failed =
            shouldThrow<StoryFailed> {
                story("waiting") {
                    Then("it gets there").eventually(within = 1.seconds, every = 1.milliseconds) {
                        check(asked.incrementAndGet() >= 3) { "not yet" }
                    }
                    And("this fails, to see the transcript") { error("stop") }
                }
            }

        failed.message!!.lines()[1] shouldContain "3 tries"
    }

    @Test
    fun `a waiting step that runs out of time shows its tries, and the last failure under it`() {
        val moving = TestClock()
        val last = AssertionError("expected:<0L> but was:<3L>")

        val failed =
            clock.locally(moving) {
                scope {
                    val telling = async {
                        shouldThrow<StoryFailed> {
                            story("draining") {
                                And("the outbox drains").eventually(5.seconds, every = 1.seconds) { throw last }
                            }
                        }
                    }
                    repeat(5) { moving.adjustWhenBlocked(1.seconds) }
                    telling.await()
                }
            }

        val lines = failed.message!!.lines()
        lines[1] shouldContain "✗ And the outbox drains"
        lines[1] shouldContain "6 tries"
        lines[2].trim() shouldBe "expected:<0L> but was:<3L>"
        failed.cause.shouldBeInstanceOf<GaveUp>().cause shouldBeSameInstanceAs last
    }

    @Test
    fun `the story is printed when it ends`() {
        val printed = ByteArrayOutputStream()
        val console = System.out
        System.setOut(PrintStream(printed, true, Charsets.UTF_8))
        try {
            story("printed") { Given("a step") {} }
        } finally {
            System.setOut(console)
        }

        val out = printed.toString(Charsets.UTF_8)
        out shouldContain "Story: printed"
        out shouldContain "Given a step"
    }

    @Test
    fun `a story takes its title from the test that tells it`() {
        val failed = shouldThrow<StoryFailed> { story { Then("it fails") { error("no") } } }

        failed.message!!.lines()[0] shouldBe "Story: a story takes its title from the test that tells it"
    }

    @Test
    fun `a failure points at the line of the assertion that failed`() {
        val failed =
            shouldThrow<StoryFailed> {
                story("pointing") {
                    Then("it fails") { "Bea" shouldBe "Ada" }
                }
            }

        val line = failed.stackTrace.first()
        line.fileName shouldBe "StoryTest.kt"
        failed.message!!.lines().last().trim() shouldBe "at $line"
    }

    @Test
    fun `a waiting step that gives up points at its last failure's line`() {
        val failed =
            shouldThrow<StoryFailed> {
                story("waiting") {
                    Then("it never gets there").eventually(within = 50.milliseconds, every = 5.milliseconds) {
                        "Bea" shouldBe "Ada"
                    }
                }
            }

        failed.stackTrace.first().fileName shouldBe "StoryTest.kt"
        failed.message!!.lines().last().trim() shouldBe "at ${failed.stackTrace.first()}"
    }
}
