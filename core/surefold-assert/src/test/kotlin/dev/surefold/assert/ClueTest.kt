package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File

class ClueTest {

    @Test
    fun `a failing check inside withClue says the clue first, then where it failed`() {
        val failed = shouldThrow<AssertionFailedError> {
            withClue("the loser is interrupted") { false shouldBe true }
        }

        failed.message shouldBe """
            the loser is interrupted
            expected: true
            but was:  false
            at ClueTest.kt:${lineOf("{ false shouldBe true }")}
                withClue("the loser is interrupted") { false shouldBe true }
        """.trimIndent()
    }

    @Test
    fun `clues nest, the outermost first, and leave with their block`() {
        val failed = shouldThrow<AssertionFailedError> {
            withClue("node v1") { withClue("shard 253") { 1 shouldBe 2 } }
        }
        failed.message!!.lines().take(2) shouldBe listOf("node v1", "shard 253")

        val after = shouldThrow<AssertionFailedError> { 1 shouldBe 2 }
        after.message!!.lines().first() shouldBe "expected: 2"
    }

    @Test
    fun `withClue returns what its block does, and builds a lazy clue only on failure`() {
        var built = 0
        withClue({ "built ${++built}" }) { 42 } shouldBe 42
        built shouldBe 0

        shouldThrow<AssertionFailedError> { withClue({ "built ${++built}" }) { 1 shouldBe 2 } }
        built shouldBe 1
    }

    @Test
    fun `a clue leaves its block even when the block throws something else`() {
        shouldThrow<IllegalStateException> { withClue("gone") { error("bug") } }

        shouldThrow<AssertionFailedError> { 1 shouldBe 2 }.message!!.lines().first() shouldBe "expected: 2"
    }
}

/** The line of this file that holds [text], as a stack frame numbers it. */
private fun lineOf(text: String): Int =
    File("src/test/kotlin/dev/surefold/assert/ClueTest.kt")
        .readLines()
        .indexOfFirst { text in it && "lineOf" !in it } + 1
