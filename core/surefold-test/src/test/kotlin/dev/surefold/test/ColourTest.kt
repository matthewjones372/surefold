package dev.surefold.test

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.shouldStartWith
import dev.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test

class ColourTest {

    private val escape = "\u001B["

    /** A story that passes one step and fails the next, told with colour [on] or off. */
    private fun told(on: Boolean): String {
        val story = Story()
        shouldThrow<IllegalStateException> {
            with(story) {
                Given("a step that passes") {}
                Then("a step that fails") { error("expected:<0L> but was:<3L>") }
            }
        }
        return story.told("coloured", on)
    }

    @Test
    fun `with colour off, nothing printed holds an escape code`() {
        told(on = false) shouldNotContain escape
    }

    @Test
    fun `with colour on, the failed step's line starts red and bold, and the passed step's tick is green`() {
        val lines = told(on = true).lines()

        lines[0] shouldStartWith "$escape${Colour.BOLD}m"
        lines[1] shouldContain "$escape${Colour.GREEN}m✓"
        lines[2] shouldStartWith "$escape${Colour.RED}m$escape${Colour.BOLD}m"
        lines[3] shouldStartWith "$escape${Colour.RED}m"
    }

    @Test
    fun `the thrown transcript holds no escape code, whatever the console gets`() {
        val failed =
            shouldThrow<StoryFailed> {
                story("plain") { Then("it fails") { error("no") } }
            }

        failed.message!! shouldNotContain escape
    }

    @Test
    fun `an explicit property wins, then NO_COLOR, then FORCE_COLOR, then IntelliJ, and otherwise it is off`() {
        Colour.decide(property = "always", noColour = "1", forceColour = null, underIntelliJ = false) shouldBe true
        Colour.decide(property = "never", noColour = null, forceColour = "1", underIntelliJ = true) shouldBe false
        Colour.decide(property = null, noColour = "1", forceColour = "1", underIntelliJ = true) shouldBe false
        Colour.decide(property = null, noColour = null, forceColour = "1", underIntelliJ = false) shouldBe true
        Colour.decide(property = null, noColour = null, forceColour = null, underIntelliJ = true) shouldBe true
        Colour.decide(property = null, noColour = null, forceColour = null, underIntelliJ = false) shouldBe false
    }

    @Test
    fun `auto, or an empty variable, leaves the decision to the rules after it`() {
        Colour.decide(property = "auto", noColour = null, forceColour = "1", underIntelliJ = false) shouldBe true
        Colour.decide(property = null, noColour = "", forceColour = "1", underIntelliJ = false) shouldBe true
        Colour.decide(property = null, noColour = null, forceColour = "", underIntelliJ = false) shouldBe false
    }
}
