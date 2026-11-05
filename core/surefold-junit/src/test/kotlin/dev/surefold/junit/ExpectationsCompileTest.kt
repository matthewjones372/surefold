package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import java.io.File
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Checks compile as statements, one after another, under the compiler options this module builds with. */
class ExpectationsCompileTest {

    private fun suiteOf(body: String, vararg imports: String) =
        """
        import dev.surefold.Resource
        import dev.surefold.junit.SharedSuite
        import dev.surefold.junit.expect
        import dev.surefold.resource
        ${imports.joinToString("\n") { "import $it" }}

        object Fixture : SharedSuite<Int>() {
            override val shared: Resource<Int> = resource { 1 }

            init {
                test("the fixture") { one -> $body }
            }
        }
    """
            .trimIndent()

    @Test
    fun `checks written one after another compile, with nothing said`(@TempDir workspace: File) {
        val source = suiteOf("expect(one == 1)\n one shouldBe 1", "dev.surefold.junit.shouldBe")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
        reported.shouldBeEmpty()
    }

    // A block of checks returns Unit, so a helper calls the one it is handed as a statement (spec 0203).
    @Test
    fun `a helper calls the block of checks it is handed as a statement, with nothing said`(@TempDir workspace: File) {
        val source =
            suiteOf("twice { expect(one == 1) }\n val check = { expect(one > 0) }\n check()")
                .replace("object Fixture", "fun twice(check: () -> Unit) {\n check()\n check()\n}\n\nobject Fixture")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
        reported.shouldBeEmpty()
    }

    @Test
    fun `a file importing both shouldBe's calls neither, until one is imported under another name`(
        @TempDir workspace: File,
    ) {
        val both =
            arrayOf(
                "dev.surefold.junit.shouldBe",
                "dev.surefold.assert.shouldBe",
            )
        val (ambiguous, reported) = EmbeddedKotlin(workspace).compile(suiteOf("one shouldBe 1", *both))

        ambiguous shouldBe ExitCode.COMPILATION_ERROR
        reported.joinToString("\n") shouldContain "ambiguity"

        val renamed = suiteOf("one shouldEqual 1; one shouldBe 1", both[0], "${both[1]} as shouldEqual")
        EmbeddedKotlin(workspace).compile(renamed).first shouldBe ExitCode.OK
    }
}
