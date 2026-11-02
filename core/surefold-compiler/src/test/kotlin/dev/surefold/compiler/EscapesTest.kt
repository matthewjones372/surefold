package dev.surefold.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

private const val IMPORTS = """
import dev.surefold.Channel
import dev.surefold.Deferred
import dev.surefold.channel
import dev.surefold.fail.attempt
import dev.surefold.scope
"""

/** Spec 0041: `DEFERRED_ESCAPES_SCOPE`. */
class EscapesTest {

    @Test
    fun `a Deferred returned from a scope is an error`() {
        val compiled = compile("$IMPORTS\nfun load() = scope { async { 1 } }")

        compiled.exit shouldBe ExitCode.COMPILATION_ERROR
        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.single().contains("scope returns a Deferred") shouldBe true
        }
    }

    @Test
    fun `the value a scope awaited is returned with no diagnostic`() {
        val compiled = compile("$IMPORTS\nfun load(): Int = scope { async { 1 }.await() }")

        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }

    @Test
    fun `a channel or Deferred held in what a typed scope answers is an error`() {
        val compiled = compile(
            """
            $IMPORTS
            fun rows() = attempt<Nothing, Channel<Int>> { scope { channel(1) } }
            fun both() = scope { 1 to listOf(async { 2 }) }
            """,
        )

        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.size shouldBe 2
            compiled.surefold.all { "scope returns a" in it } shouldBe true
        }
    }

    @Test
    fun `a Deferred in a property is an error, and in a local is not`() {
        val compiled = compile(
            """
            $IMPORTS
            class Pending { var load: Deferred<Int>? = null }
            lateinit var rows: Channel<Int>
            fun local(): Int = scope {
                val load: Deferred<Int> = async { 1 }
                load.await()
            }
            """,
        )

        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.size shouldBe 2
            compiled.surefold.any { "load keeps a Deferred" in it } shouldBe true
            compiled.surefold.any { "rows keeps a Channel" in it } shouldBe true
        }
    }
}
