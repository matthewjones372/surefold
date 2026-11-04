package dev.surefold.guides

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The complete examples in `docs/core.md`, `docs/testing.md` and the README's suite, each compiled on its own against
 * this module's test classpath: the core, surefold-cache, surefold-junit and surefold-test, and kotlinx-coroutines
 * for the core guide's interop. Nothing that wraps an outside library is on it, so an example that needs one fails.
 */
class CoreGuidesSuite {

    private val pages =
        listOf(
            "docs/core.md" to "core",
            "docs/testing.md" to "testing",
            "README.md" to "readme",
        )

    @TempDir
    lateinit var workspace: File

    private fun page(path: String): String {
        val root = System.getProperty("surefold.guides.repoRoot")
        withClue("the build must pass -Dsurefold.guides.repoRoot; see core/surefold-guides/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, path).readText()
    }

    /** Every fence marked `<!-- prefix-name -->` on the page, by its marker. */
    private fun fences(path: String, prefix: String): Map<String, String> =
        Regex("""<!-- ($prefix-[a-z-]+) -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page(path)).associate { it.groupValues[1] to it.groupValues[2] }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(File(workspace, "${source.hashCode()}").apply { mkdirs() }).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every marked example in the guides compiles against the library`() {
        pages.forEach { (path, prefix) ->
            val marked = fences(path, prefix)
            withClue("$path must carry examples marked <!-- $prefix-… -->, or this test asserts nothing") {
                marked.keys.shouldNotBeEmpty()
            }
            marked.forEach { (marker, source) -> withClue("$path $marker") { errors(source).shouldBeEmpty() } }
        }
    }

    @Test
    fun `the core guide demonstrates each cache retention choice`() {
        val examples = fences("docs/core.md", "core")
        listOf("core-cache-zero", "core-cache-infinite", "core-cache-finite").forEach { marker ->
            withClue("the retention guide needs a compiled example for $marker") {
                examples.keys shouldContain marker
            }
        }
    }

    @Test
    fun `the core guide demonstrates explicit awaited cache release`() {
        withClue("the release guide needs a compiled resource example") {
            fences("docs/core.md", "core").keys shouldContain "core-cache-release"
        }
    }

    @Test
    fun `the core guide demonstrates a nontransactional ownership boundary`() {
        fences("docs/core.md", "core").keys shouldContain "core-transaction-boundary"
    }

    @Test
    fun `the core guide demonstrates a saga`() {
        fences("docs/core.md", "core").keys shouldContain "core-saga"
    }

    @Test
    fun `the testing guide tells a story in a suite`() {
        fences("docs/testing.md", "testing").keys shouldContain "testing-junit-story"
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        val forking = fences("docs/core.md", "core").getValue("core-forking")
        errors(forking.replace("parZip(", "parZipped(")).shouldNotBeEmpty()
    }

    @Test
    fun `every marker on a page is unique`() {
        pages.forEach { (path, prefix) ->
            val markers = Regex("""<!-- ($prefix-[a-z-]+) -->""").findAll(page(path)).map { it.groupValues[1] }.toList()
            withClue(path) { markers.size shouldBe markers.toSet().size }
        }
    }
}
