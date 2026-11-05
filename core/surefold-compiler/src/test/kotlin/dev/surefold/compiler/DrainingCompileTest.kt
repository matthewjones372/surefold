package dev.surefold.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

class DrainingCompileTest {
    @Test
    fun `cleanup bodies permit non-local returns`() {
        val compiled =
            compile(
                """
            import dev.surefold.draining
            import dev.surefold.uninterruptible
            import dev.surefold.onThrow
            fun drain(): String { draining { return "done" } }
            fun waitForEnd(): String { uninterruptible { return "done" } }
            fun acquire(): String { onThrow({ println(it::class) }) { return "done" } }
            """,
            )
        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }

    @Test
    fun `the eaten-failure checker flags a raw interrupt catch around await but not the primitives`() {
        val compiled =
            compile(
                """
            import dev.surefold.draining
            import dev.surefold.fail.Either
            import dev.surefold.fail.attempt
            import dev.surefold.fail.bind
            import dev.surefold.onThrow
            import dev.surefold.scope
            import dev.surefold.uninterruptible
            val rows: Either<String, Int> = Either.Right(1)
            fun raw(): Int = scope {
                val d = async { 1 }
                try { d.await() } catch (e: InterruptedException) { 0 }
            }
            fun drained(): Int = scope {
                val d = async { 1 }
                draining { uninterruptible { d.await() } }
            }
            fun observed() = attempt<String, Int> { onThrow({ println(it) }) { rows.bind() } }
            """,
            )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.OK
            compiled.surefold.size shouldBe 1
            ("catch (e: InterruptedException) takes what await ends with" in compiled.surefold.single()) shouldBe true
        }
    }

    @Test
    fun `throw observers cannot recover through a non-local return`() {
        val compiled =
            compile(
                """
            import dev.surefold.onThrow
            fun escaped(): Int = onThrow({ return 42 }) { error("original") }
            """,
            )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.COMPILATION_ERROR
            compiled.messages.any { "Unresolved reference" in it } shouldBe false
        }
    }

    @Test
    fun `interrupt observers cannot finish a wait through a non-local return`() {
        val compiled =
            compile(
                """
            import dev.surefold.uninterruptible
            fun escaped(): Int = uninterruptible(onInterrupted = { return 42 }) { 1 }
            """,
            )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.COMPILATION_ERROR
            compiled.messages.any { "Unresolved reference" in it } shouldBe false
        }
    }
}
