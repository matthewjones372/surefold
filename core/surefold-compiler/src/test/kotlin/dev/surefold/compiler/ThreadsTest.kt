package dev.surefold.compiler

import dev.surefold.assert.shouldBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test

private const val IMPORTS =
    """
import dev.surefold.fail.attempt
import dev.surefold.scope
"""

/** Spec 0041: `AWAIT_ACROSS_FORK` and `RAISE_ACROSS_FORK`. */
class ThreadsTest {

    @Test
    fun `awaiting another fork's Deferred inside an async block is an error`() {
        val compiled =
            compile(
                """
            $IMPORTS
            fun load(): Int = scope {
                val a = async { 1 }
                val b = async { a.await() + 1 }
                b.await()
            }
            """,
            )

        compiled.exit shouldBe ExitCode.COMPILATION_ERROR
        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.single().contains("await on a, a Deferred from outside this async block") shouldBe true
        }
    }

    @Test
    fun `cancelling one from a nested async block is an error too, reported once`() {
        val compiled =
            compile(
                """
            $IMPORTS
            fun load(): Int = scope {
                val a = async { 1 }
                async { async { a.cancel() }.await() }.await()
                0
            }
            """,
            )

        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.single().contains("cancel on a") shouldBe true
        }
    }

    @Test
    fun `awaiting in the scope's own block, or a Deferred the async block opened, compiles`() {
        val compiled =
            compile(
                """
            $IMPORTS
            fun load(): Int = scope {
                val a = async { 1 }
                val b = async {
                    val inner = async { 2 }
                    inner.await()
                }
                a.await() + b.await()
            }
            """,
            )

        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }

    @Test
    fun `failing an outer attempt by label from inside an async block is an error`() {
        val compiled =
            compile(
                """
            $IMPORTS
            fun load() = attempt<String, Int> outer@{
                scope { async { this@outer.fail("gone") }.await() }
            }
            """,
            )

        compiled.exit shouldBe ExitCode.COMPILATION_ERROR
        withClue(compiled.messages.joinToString("\n")) {
            compiled.surefold.single().contains("this@outer fails a scope from a thread it does not own") shouldBe true
        }
    }

    @Test
    fun `failing through the async block's own scope compiles`() {
        val compiled =
            compile(
                """
            $IMPORTS
            fun load() = attempt<String, Int> {
                scope { async { fail("gone") }.await() }
            }
            fun labelled() = attempt<String, Int> {
                scope { async inner@{ this@inner.fail("gone") }.await() }
            }
            """,
            )

        withClue(compiled.messages.joinToString("\n")) { compiled.exit shouldBe ExitCode.OK }
    }
}
