package dev.surefold.guides

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
 * Every recipe in `docs/cookbook.md`, compiled against surefold-app and the core alone. A page nobody compiles goes
 * wrong quietly, and this one is the first thing a reader copies. The recipes on Typesafe Config, kotlinx and Pekko
 * are surefold-integrations' guides now, and compiled there (spec 0302).
 */
class CookbookSuite {

    private val fixtures = "<!-- cookbook-fixtures -->"
    private val recipe = "<!-- cookbook -->"

    @TempDir
    lateinit var workspace: File

    private fun page(): File {
        val root = System.getProperty("surefold.guides.repoRoot")
        withClue("the build must pass -Dsurefold.guides.repoRoot; see core/surefold-guides/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cookbook.md")
    }

    private fun fences(marker: String): List<String> =
        Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page().readText()).map { it.groupValues[1] }.toList()

    @Test
    fun `the page carries exactly one fixtures fence`() {
        fences(fixtures).size shouldBe 1
    }

    @Test
    fun `every recipe compiles against the library it documents`() {
        val recipes = fences(recipe)
        withClue("the page must carry recipes, or this test is asserting nothing") { recipes.shouldNotBeEmpty() }

        // One compilation rather than one each: a recipe builds on the value the one above it named, which is how a
        // reader meets them.
        val page = (listOf(fences(fixtures).single()) + recipes).joinToString("\n\n")
        val (exit, errors) = EmbeddedKotlin(workspace).compile(page)
        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }
}
