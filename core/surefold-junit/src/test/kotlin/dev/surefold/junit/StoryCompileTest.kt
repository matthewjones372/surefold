package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which `story` a suite calls, under the compiler options this module builds with. */
class StoryCompileTest {

    private fun suiteOf(body: String, vararg imports: String) = """
        import dev.surefold.Resource
        import dev.surefold.junit.SharedSuite
        import dev.surefold.junit.shouldBe
        import dev.surefold.resource
        ${imports.joinToString("\n") { "import $it" }}

        object Fixture : SharedSuite<Int>() {
            override val shared: Resource<Int> = resource { 1 }

            init {
                test("the fixture") { one -> $body }
            }
        }
    """.trimIndent()

    private val steps = """
        story {
            Given("one") { }
            Then("it is one") { one shouldBe 1 }
            Then("it is not two") { one shouldBe 1 }
        }
    """.trimIndent()

    @Test
    fun `a suite importing surefold-junit's story compiles, with nothing said`(@TempDir workspace: File) {
        val source = suiteOf(steps, "dev.surefold.junit.story")
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
        reported.shouldBeEmpty()
    }

    @Test
    fun `a file importing both stories calls neither, until one is imported under another name`(
        @TempDir workspace: File,
    ) {
        val both = arrayOf(
            "dev.surefold.junit.story",
            "dev.surefold.test.story",
        )
        val (ambiguous, reported) = EmbeddedKotlin(workspace).compile(suiteOf(steps, *both))

        ambiguous shouldBe ExitCode.COMPILATION_ERROR
        reported.joinToString("\n") shouldContain "ambiguity"

        val renamed = suiteOf(steps, both[0], "${both[1]} as jupiterStory")
        val (exit, said) = EmbeddedKotlin(workspace).compile(renamed)
        withClue(said.joinToString("\n")) { exit shouldBe ExitCode.OK }
    }

    // A step's check is recorded when it is made, so a step left out of any value still counts (spec 0202).
    @Test
    fun `a dropped then still compiles`(@TempDir workspace: File) {
        val source = suiteOf(
            "story { Then(\"it is two\") { one shouldBe 2 }\n Then(\"it is one\") { one shouldBe 1 } }",
            "dev.surefold.junit.story",
        )
        val (exit, reported) = EmbeddedKotlin(workspace).compile(source)

        withClue(reported.joinToString("\n")) { exit shouldBe ExitCode.OK }
    }
}
