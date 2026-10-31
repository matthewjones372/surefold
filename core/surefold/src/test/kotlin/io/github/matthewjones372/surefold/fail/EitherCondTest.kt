package io.github.matthewjones372.surefold.fail

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldBeSameInstanceAs
import io.github.matthewjones372.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.util.concurrent.CancellationException

class EitherCondTest {
    @Test
    fun `true constructs a right and runs only its callback once`() {
        val payload = Any()
        var rights = 0
        var lefts = 0
        val answer: Either<String, Any> = Either.cond(
            condition = true,
            right = { rights++; payload },
            left = { lefts++; error("unselected left") },
        )
        answer.getOrNull() shouldBeSameInstanceAs payload
        rights shouldBe 1
        lefts shouldBe 0
    }

    @Test
    fun `false constructs a left and runs only its callback once`() {
        val failure = Any()
        var rights = 0
        var lefts = 0
        val answer: Either<Any, Int> = Either.cond(
            condition = false,
            right = { rights++; error("unselected right") },
            left = { lefts++; failure },
        )
        answer.leftOrNull() shouldBeSameInstanceAs failure
        rights shouldBe 0
        lefts shouldBe 1
    }

    @Test
    fun `both sides support null payloads and inferred generic types`() {
        val right: Either<String?, Int?> = Either.cond(true, right = { null }, left = { "unused" })
        val left: Either<String?, Int?> = Either.cond(false, right = { 1 }, left = { null })
        right shouldBe Either.Right(null)
        left shouldBe Either.Left(null)
        val inferred = Either.cond(false, right = { 1 }, left = { "no" })
        val fixed: Either<String, Int> = inferred
        fixed shouldBe "no".left()
    }

    @Test
    fun `the condition is evaluated once`() {
        var conditions = 0
        val answer = Either.cond(++conditions == 1, right = { 1 }, left = { "no" })
        conditions shouldBe 1
        answer shouldBe 1.right()
    }

    @Test
    fun `callback failures propagate unchanged from either selected side`() {
        val failures = listOf(
            IllegalStateException("failed"), InterruptedException("stopped"),
            CancellationException("cancelled"), LinkageError("fatal"),
        )
        failures.forEach { failure ->
            shouldThrow<Throwable> {
                Either.cond<String, Int>(true, right = { throw failure }, left = { "unused" })
            } shouldBeSameInstanceAs failure
            shouldThrow<Throwable> {
                Either.cond<String, Int>(false, right = { 1 }, left = { throw failure })
            } shouldBeSameInstanceAs failure
        }
    }

    @Test
    fun `typed failures unwind the surrounding attempt from either selected side`() {
        listOf(true, false).forEach { condition ->
            attempt<String, Int> {
                Either.cond<String, Int>(condition, right = { fail("right") }, left = { fail("left") }).bind()
            } shouldBe if (condition) "right".left() else "left".left()
        }
    }

    @Test
    fun `inline callbacks support non local returns`() {
        returnedFromCond(true) shouldBe "right"
        returnedFromCond(false) shouldBe "left"
    }
}

private fun returnedFromCond(condition: Boolean): String {
    Either.cond<String, Int>(condition, right = { return "right" }, left = { return "left" })
    return "not returned"
}
