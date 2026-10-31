package io.github.matthewjones372.surefold.fail

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.shouldThrow
import org.junit.jupiter.api.Test
import java.io.IOException
import java.util.concurrent.CancellationException

class EitherRecoverTest {
    @Test
    fun `total recovery returns a value without running on success`() {
        val success: Either<String, Int> = 7.right()
        val passed: Either<Nothing, Int> = success.recover { error("not called") }
        (passed === success) shouldBe true
        val failure: Either<String, Int> = "missing".left()
        val recovered: Either<Nothing, Int> = failure.recover { it.length }
        recovered shouldBe 7.right()
    }

    @Test
    fun `total recovery can bind an alternative with a new error type`() {
        val original: Either<String, Int> = "missing".left()
        val failed: Either<Int, Int> = original.recover { 42.left().bind() }
        failed shouldBe 42.left()
        val backup: Either<Nothing, Int> = original.recover { 9.right().bind() }
        backup shouldBe 9.right()
        val replaced: Either<Int, Int> = original.recover { fail(it.length) }
        replaced shouldBe 7.left()
    }

    @Test
    fun `selective recovery runs once for subtypes and keeps unmatched object identity`() {
        var calls = 0
        val expected = SpecialIo()
        val selected: Either<Throwable, String> = expected.left()
        selected.recover(IOException::class) {
            (it === expected) shouldBe true
            calls++
            "backup"
        } shouldBe "backup".right()
        calls shouldBe 1
        val unexpected = IllegalStateException("unexpected")
        val unmatched: Either<Throwable, String> = unexpected.left()
        val kept = unmatched.recover(IOException::class) { error("not called") }
        (kept === unmatched) shouldBe true
        (kept.leftOrNull() === unexpected) shouldBe true
        val success: Either<Throwable, String> = "primary".right()
        (success.recover(IOException::class) { error("not called") } === success) shouldBe true
    }

    @Test
    fun `selective recovery supports typed bind and replacement failures`() {
        val original: Either<Throwable, String> = IOException().left()
        val replacement = IllegalArgumentException("backup unavailable")
        val failed = original.recover(IOException::class) { replacement.left().bind() }
        (failed.leftOrNull() === replacement) shouldBe true
        original.recover(IOException::class) { "backup".right().bind() } shouldBe "backup".right()
        val replaced = original.recover(IOException::class) { fail(replacement) }
        (replaced.leftOrNull() === replacement) shouldBe true
    }

    @Test
    fun `boxed class tokens and nullable values preserve their distinctions`() {
        val number: Either<Any?, String?> = 42.left()
        number.recover(Int::class) { null } shouldBe null.right()
        val nullError: Either<Any?, String?> = null.left()
        (nullError.recover(Any::class) { error("null cannot match") } === nullError) shouldBe true
        val total: Either<Nothing, String?> = nullError.recover { null }
        total shouldBe null.right()
        val nullSuccess: Either<Any?, String?> = null.right()
        (nullSuccess.recover(Int::class) { error("not called") } === nullSuccess) shouldBe true
    }

    @Test
    fun `recovery belongs to its own Fail scope`() {
        val outside = attempt<String, Int> {
            val parent = this
            val failed: Either<Throwable, Int> = IOException().left()
            failed.recover(IOException::class) { parent.fail("outer") }.getOrNull() ?: 0
        }
        outside shouldBe "outer".left()
    }

    @Test
    fun `replacement failures may be null on either recovery path`() {
        val original: Either<String?, Int> = "missing".left()
        original.recover(String::class) { fail(null) } shouldBe null.left()
        val total: Either<String?, Int> = original.recover { fail(null) }
        total shouldBe null.left()
    }

    @Test
    fun `total recovery does not intercept an enclosing Fail`() {
        val outside = attempt<String, Int> {
            val parent = this
            val original: Either<Int, Int> = 1.left()
            val result: Either<Int, Int> = original.recover { parent.fail("outer") }
            result.getOrNull() ?: 0
        }
        outside shouldBe "outer".left()
    }

    @Test
    fun `handler exceptions and runtime control signals are not implicitly captured`() {
        val failures = listOf(
            IllegalStateException("handler"),
            InterruptedException("interrupt"),
            CancellationException("cancel"),
            LinkageError("fatal"),
        )
        val original: Either<Throwable, Int> = IOException().left()
        failures.forEach { thrown ->
            val selected = shouldThrow<Throwable> { original.recover(IOException::class) { throw thrown } }
            (selected === thrown) shouldBe true
            val total = shouldThrow<Throwable> { original.recover<Throwable, Nothing, Int> { throw thrown } }
            (total === thrown) shouldBe true
        }
    }
}

private class SpecialIo : IOException()
