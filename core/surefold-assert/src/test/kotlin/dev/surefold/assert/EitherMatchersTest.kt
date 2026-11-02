package dev.surefold.assert

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import org.junit.jupiter.api.Test
import org.opentest4j.AssertionFailedError
import java.io.File

class EitherMatchersTest {

    private data class Bad(val why: String)

    private val right: Either<Bad, Int> = 42.right()
    private val left: Either<Bad, Int> = Bad("broken").left()

    @Test
    fun `shouldBeRight returns the value of a Right`() {
        right.shouldBeRight() shouldBe 42
        (right shouldBeRight 42) shouldBe 42
    }

    @Test
    fun `shouldBeLeft returns the error of a Left`() {
        left.shouldBeLeft() shouldBe Bad("broken")
        (left shouldBeLeft Bad("broken")) shouldBe Bad("broken")
    }

    @Test
    fun `a failure says what was expected and what was found, then where and the line itself`() {
        val failed = shouldThrow<AssertionFailedError> { left shouldBeRight 42 }

        failed.message shouldBe """
            expected: Right(value=42)
            but was:  Left(value=Bad(why=broken))
            at EitherMatchersTest.kt:${lineOf("{ left shouldBeRight 42 }")}
                val failed = shouldThrow<AssertionFailedError> { left shouldBeRight 42 }
        """.trimIndent()
    }

    @Test
    fun `a failure's stack starts at the test, not inside the matcher`() {
        val failed = shouldThrow<AssertionFailedError> { right.shouldBeLeft() }

        val top = failed.stackTrace.first()
        top.fileName shouldBe "EitherMatchersTest.kt"
        top.lineNumber shouldBe lineOf("{ right.shouldBeLeft() }")
    }

    @Test
    fun `a failure carries expected and actual for the IDE to compare`() {
        val failed = shouldThrow<AssertionFailedError> { right shouldBeLeft Bad("broken") }

        failed.expected.stringRepresentation shouldBe "Left(value=Bad(why=broken))"
        failed.actual.stringRepresentation shouldBe "Right(value=42)"
    }

    @Test
    fun `asserting only the side names the side expected`() {
        val failed = shouldThrow<AssertionFailedError> { left.shouldBeRight() }

        failed.message!!.lines().take(2) shouldBe listOf("expected: a Right", "but was:  Left(value=Bad(why=broken))")
    }

    @Test
    fun `values that print alike but differ in type are shown with their types`() {
        val long: Either<Bad, Any> = 42L.right()

        val failed = shouldThrow<AssertionFailedError> { long shouldBeRight 42 }

        failed.message!!.lines().take(2) shouldBe listOf(
            "expected: Right(value=42) of kotlin.Int",
            "but was:  Right(value=42) of kotlin.Long",
        )
    }

    @Test
    fun `a Right holding null is not taken for a Left`() {
        val nothing: Either<Bad, Int?> = null.right()

        (nothing shouldBeRight null) shouldBe null
        shouldThrow<AssertionFailedError> { nothing.shouldBeLeft() }
    }
}

/** The line of this file that holds [text], as a stack frame numbers it. */
private fun lineOf(text: String): Int =
    File("src/test/kotlin/dev/surefold/assert/EitherMatchersTest.kt")
        .readLines()
        .indexOfFirst { text in it && "lineOf" !in it } + 1
