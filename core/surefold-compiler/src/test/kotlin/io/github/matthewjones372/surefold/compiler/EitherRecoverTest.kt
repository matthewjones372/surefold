package io.github.matthewjones372.surefold.compiler

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

class EitherRecoverTest {
    @Test
    fun `recovery infers selected handlers and permits a different total error type`() {
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.fail.Either
            import io.github.matthewjones372.surefold.fail.recover
            import io.github.matthewjones372.surefold.fail.bind
            import java.io.IOException

            fun selected(value: Either<Throwable, String?>): Either<Throwable, String?> =
                value.recover(IOException::class) { null }

            fun total(value: Either<String, Int>): Either<Nothing, Int> = value.recover { it.length }

            fun changed(value: Either<String, Int>): Either<Int, Int> = value.recover { fail(it.length) }

            fun inferred(value: Either<String, Int>) {
                val result: Either<Nothing, Int> = value.recover { it.length }
                val checked: Either<Nothing, Int> = result
                println(checked)
            }

            fun alternative(): Either<Throwable, String?> = Either.Right("backup")
            fun binding(value: Either<Throwable, String?>): Either<Throwable, String?> =
                value.recover(IOException::class) { alternative().bind() }

            fun boxed(value: Either<Any?, String?>): Either<Any?, String?> = value.recover(Int::class) { null }
            """,
        )
        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }

    @Test
    fun `selective recovery cannot widen an inferred error type through fail`() {
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.fail.Either
            import java.io.IOException

            fun rejected(value: Either<Throwable, String>) {
                val recovered = value.recover(IOException::class) { fail("wrong error type") }
                println(recovered)
            }
            """,
        )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.COMPILATION_ERROR
            compiled.messages.any { "Unresolved reference" in it } shouldBe false
            compiled.messages.any { "mismatch" in it.lowercase() } shouldBe true
        }
    }

    @Test
    fun `selective recovery cannot change an inferred success type`() {
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.fail.Either
            import java.io.IOException

            fun rejected(value: Either<Throwable, Int>) {
                val recovered = value.recover(IOException::class) { "wrong value type" }
                println(recovered)
            }
            """,
        )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.COMPILATION_ERROR
            compiled.messages.any { "Unresolved reference" in it } shouldBe false
        }
    }
}
