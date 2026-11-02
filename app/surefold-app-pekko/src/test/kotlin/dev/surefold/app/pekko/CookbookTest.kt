package dev.surefold.app.pekko

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Every recipe in `docs/cookbook.md`, compiled against the library it documents. A page nobody
 * compiles is a page that goes wrong quietly, and this one is the first thing a reader copies.
 */
class CookbookTest {

    companion object {
        private const val FIXTURES = "<!-- cookbook-fixtures -->"
        private const val RECIPE = "<!-- cookbook -->"
        private const val PEKKO = "<!-- cookbook-pekko -->"
    }

    @TempDir
    lateinit var workspace: File

    private fun page(): File {
        val root = System.getProperty("surefold.app.pekko.repoRoot")
        withClue("the build must pass -Dsurefold.app.pekko.repoRoot; see surefold-app-pekko/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cookbook.md")
    }

    private fun marked(marker: String) = Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    private fun fences(marker: String): List<String> =
        marked(marker).findAll(page().readText()).map { match -> match.groupValues[1] }.toList()

    private fun compiles(source: String) {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)

        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }

    @Test
    fun `the page carries exactly one fixtures fence`() {
        fences(FIXTURES).size shouldBe 1
    }

    @Test
    fun `the configuration recipe compiles its class form`() {
        withClue("a section read from its @Serializable class is the form a reader copies first") {
            fences(RECIPE).any { recipe -> "@Serializable" in recipe } shouldBe true
        }
    }

    @Test
    fun `every recipe compiles against the library it documents`() {
        val fixtures = fences(FIXTURES).single()
        val recipes = fences(RECIPE) + fences(PEKKO)

        withClue("the page must carry recipes, or this test is asserting nothing") {
            recipes.shouldNotBeEmpty()
        }

        // One compilation rather than one each: a recipe builds on the value the one above it named,
        // which is how a reader meets them.
        compiles((listOf(fixtures) + recipes).joinToString("\n\n"))
    }
}
