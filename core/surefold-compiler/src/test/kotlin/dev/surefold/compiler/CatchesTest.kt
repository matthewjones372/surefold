package dev.surefold.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

private const val IMPORTS = """
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.right
import dev.surefold.scope
import java.io.IOException

val rows: Either<String, Int> = 1.right()
"""

/** Spec 0041: `CATCH_EATS_RAISE`. */
class CatchesTest {

    @Test
    fun `a Throwable caught around bind and kept warns`() {
        val compiled =
            compile("$IMPORTS\nfun load() = attempt<String, Int> { try { rows.bind() } catch (e: Throwable) { 0 } }")

        withClue(compiled.messages.joinToString("\n")) {
            compiled.exit shouldBe ExitCode.OK
            compiled.surefold.single().startsWith("WARNING") shouldBe true
            compiled.surefold.single().contains("catch (e: Throwable) takes what bind ends with") shouldBe true
        }
    }

    @Test
    fun `an InterruptedException or Exception caught around await and kept warns`() {
        val compiled = compile(
            """
            $IMPORTS
            fun interrupted(): Int = scope {
                val d = async { 1 }
                try { d.await() } catch (e: InterruptedException) { 0 }
            }
            fun any(): Int = scope {
                val d = async { 1 }
                try { d.await() } catch (e: Exception) { 0 }
            }
            """,
        )

        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.size shouldBe 2
            compiled.surefold.all { "takes what await ends with" in it } shouldBe true
        }
    }

    @Test
    fun `a catch that rethrows, puts the interrupt back, or names its exceptions does not warn`() {
        val compiled = compile(
            """
            $IMPORTS
            fun rethrows() = attempt<String, Int> { try { rows.bind() } catch (e: Throwable) { throw e } }
            fun restores(): Int = scope {
                val d = async { 1 }
                try { d.await() } catch (e: Exception) { Thread.currentThread().interrupt(); 0 }
            }
            fun named(): Int = scope {
                val d = async { 1 }
                try { d.await() } catch (e: IOException) { 0 }
            }
            """,
        )

        withClue(compiled.messages.joinToString("\n")) { compiled.surefold shouldBe emptyList() }
    }

    @Test
    fun `an Exception caught around bind does not warn, since fail's throwable is not an Exception`() {
        val compiled =
            compile("$IMPORTS\nfun load() = attempt<String, Int> { try { rows.bind() } catch (e: Exception) { 0 } }")

        withClue(compiled.messages.joinToString("\n")) { compiled.surefold shouldBe emptyList() }
    }
}
