package dev.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldThrow
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URLClassLoader

/**
 * The examples in `docs/actors.md`, compiled out of the page (spec 0089): each fence marked `<!-- actors-… -->`, a
 * marker that renders as nothing and survives the heading above it being reworded. The testing section's tests are
 * run as well, since a test the page shows must pass.
 */
class ActorsGuideTest {

    private val markers =
        listOf("actor", "failure", "time", "routing", "persistent", "versioned", "batching", "testing")
            .map { "<!-- actors-$it -->" }

    private val testing = "<!-- actors-testing -->"

    @TempDir
    lateinit var workspace: File

    private fun repo(path: String): String {
        val root = System.getProperty("surefold.actor.repoRoot")
        withClue("the build must pass -Dsurefold.actor.repoRoot; see surefold-actor/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, path).readText()
    }

    private fun guide(): String = repo("docs/actors.md")

    private fun fences(marker: String): List<String> =
        Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(guide()).map { it.groupValues[1] }.toList()

    private fun only(marker: String): String {
        withClue("docs/actors.md must hold one kotlin fence marked $marker") { fences(marker).size shouldBe 1 }
        return fences(marker).single()
    }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    /**
     * Compiles [source] and runs each `@Test` method in it, as JUnit would, on a new instance of its class: the names
     * of those that ran, or the first failure, as thrown by the test itself.
     */
    private fun run(source: String): List<String> {
        errors(source).shouldBeEmpty()
        val loader = URLClassLoader(arrayOf(File(workspace, "out").toURI().toURL()), javaClass.classLoader)
        val classes = File(workspace, "out").walk().filter { it.extension == "class" }
            .map { it.relativeTo(File(workspace, "out")).path.removeSuffix(".class").replace(File.separatorChar, '.') }
            .map(loader::loadClass)
        return classes.flatMap { type ->
            type.methods.filter { it.isAnnotationPresent(Test::class.java) }.map { test ->
                try {
                    test.invoke(type.getDeclaredConstructor().newInstance())
                } catch (failed: InvocationTargetException) {
                    throw failed.cause ?: failed
                }
                test.name
            }
        }.toList()
    }

    @Test
    fun `every marked example on the page compiles against the library`() {
        markers.forEach { marker ->
            withClue(marker) { errors(only(marker)).shouldBeEmpty() }
        }
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        errors(only(markers.first()).replace("unhandled()", "ignored()")).shouldNotBeEmpty()
    }

    @Test
    fun `the testing section's tests pass when run`() {
        run(only(testing)).size shouldBe 3
    }

    @Test
    fun `a testing example that claims what is not so fails when run`() {
        shouldThrow<AssertionError> { run(only(testing).replace("250.right()", "251.right()")) }
    }

    @Test
    fun `the README's surefold-actor row and the cluster guide's opening link the page`() {
        val row = repo("README.md").lines().single { it.startsWith("| `surefold-actor` |") }
        row.contains("(docs/actors.md)") shouldBe true
        repo("docs/cluster.md").substringBefore("\n## ").contains("(actors.md)") shouldBe true
    }

    @Test
    fun `every marker on the page is one this test compiles`() {
        val onPage = Regex("""<!-- actors-[a-z-]+ -->""").findAll(guide()).map { it.value }.toList()

        onPage shouldBe markers
    }
}
