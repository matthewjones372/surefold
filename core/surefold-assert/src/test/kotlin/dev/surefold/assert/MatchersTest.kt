package dev.surefold.assert

import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError

class MatchersTest {

    @Test
    fun `shouldBe returns what it was given when it matches`() {
        (listOf(1, 2) shouldBe listOf(1, 2)) shouldBe listOf(1, 2)
    }

    @Test
    fun `shouldBe names both values and the line that compared them`() {
        val failed = shouldThrow<AssertionFailedError> { "rex" shouldBe "bo" }

        failed.message!!.lines().take(2) shouldBe listOf("expected: bo", "but was:  rex")
        failed.message!!.lines()[3].trim() shouldBe
            "val failed = shouldThrow<AssertionFailedError> { \"rex\" shouldBe \"bo\" }"
    }

    // shouldThrow's own claims are checked without it: a broken shouldThrow could not be trusted to report itself.
    @Test
    fun `shouldThrow returns the throwable the block threw`() {
        val bug = IllegalStateException("bug")

        val thrown = shouldThrow<IllegalStateException> { throw bug }

        if (thrown !== bug) throw AssertionError("shouldThrow returned $thrown, not what was thrown")
    }

    @Test
    fun `shouldThrow fails when the block throws nothing`() {
        val failed =
            try {
                shouldThrow<IllegalStateException> { 42 }
                null
            } catch (expected: AssertionFailedError) {
                expected
            }

        if (
            failed?.message?.lines()?.take(2) !=
                listOf(
                    "expected: a java.lang.IllegalStateException thrown",
                    "but was:  nothing thrown",
                )
        ) {
            throw AssertionError("shouldThrow did not fail as it should: ${failed?.message}")
        }
    }

    @Test
    fun `shouldThrow fails on another type, and keeps it as the cause`() {
        val other = IllegalArgumentException("other")
        val failed =
            try {
                shouldThrow<IllegalStateException> { throw other }
                null
            } catch (expected: AssertionFailedError) {
                expected
            }

        if (failed?.cause !== other) throw AssertionError("shouldThrow did not fail with the other throwable as cause")
    }
}
