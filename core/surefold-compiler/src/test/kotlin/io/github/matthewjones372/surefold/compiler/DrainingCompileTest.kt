package io.github.matthewjones372.surefold.compiler

import io.github.matthewjones372.surefold.assert.shouldBe
import io.github.matthewjones372.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

class DrainingCompileTest {
    @Test
    fun `cleanup bodies permit non-local returns`() {
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.draining
            import io.github.matthewjones372.surefold.uninterruptible
            import io.github.matthewjones372.surefold.onThrow
            fun drain(): String { draining { return "done" } }
            fun waitForEnd(): String { uninterruptible { return "done" } }
            fun acquire(): String { onThrow({ println(it::class) }) { return "done" } }
            """,
        )
        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }

    @Test
    fun `throw observers cannot recover through a non-local return`() {
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.onThrow
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
        val compiled = compile(
            """
            import io.github.matthewjones372.surefold.uninterruptible
            fun escaped(): Int = uninterruptible(onInterrupted = { return 42 }) { 1 }
            """,
        )
        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.COMPILATION_ERROR
            compiled.messages.any { "Unresolved reference" in it } shouldBe false
        }
    }
}
