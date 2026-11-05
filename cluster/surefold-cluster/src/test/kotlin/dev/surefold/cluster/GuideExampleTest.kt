package dev.surefold.cluster

import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import java.io.File
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The examples in `docs/cluster.md`, compiled out of the page (spec 0084): each fence marked `<!-- cluster-… -->`, a
 * marker that renders as nothing and survives the heading above it being reworded.
 */
class GuideExampleTest {

    private val markers =
        listOf(
                "remote",
                "tls",
                "membership",
                "entities",
                "persistent",
                "read-model",
                "sharded",
                "reliable",
                "reliable-answers",
                "keyed",
                "saga",
                "operating",
                "topic",
            )
            .map { "<!-- cluster-$it -->" }

    @TempDir lateinit var workspace: File

    private fun guide(): String {
        val root = System.getProperty("surefold.cluster.repoRoot")
        withClue("the build must pass -Dsurefold.cluster.repoRoot; see surefold-cluster/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, "docs/cluster.md").readText()
    }

    private fun fences(marker: String): List<String> =
        Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(guide())
            .map { it.groupValues[1] }
            .toList()

    private fun only(marker: String): String {
        withClue("docs/cluster.md must hold one kotlin fence marked $marker") { fences(marker).size shouldBe 1 }
        return fences(marker).single()
    }

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every marked example on the page compiles against the library`() {
        markers.forEach { marker ->
            withClue(marker) { errors(only(marker)).shouldBeEmpty() }
        }
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        errors(only(markers.first()).replace("node.expose(", "node.publish(")).shouldNotBeEmpty()
    }

    @Test
    fun `the README's cluster, remote and journal rows link the guide`() {
        val readme = File(System.getProperty("surefold.cluster.repoRoot"), "README.md").readLines()
        listOf("surefold-cluster", "surefold-actor-remote", "surefold-actor-journal-jdbc").forEach { module ->
            val row = readme.single { it.startsWith("| `$module` |") }
            withClue(module) { row.contains("(docs/cluster.md)") shouldBe true }
        }
    }

    @Test
    fun `the DDL the page tells a service to apply is where the journal's jar ships it`() {
        val named =
            Regex("""`(surefold/journal/jdbc/[a-z0-9]+\.sql)`""").findAll(guide()).map { it.groupValues[1] }.toSet()

        named shouldBe setOf("surefold/journal/jdbc/postgres.sql")
        named.forEach { path -> withClue(path) { JdbcJournal::class.java.getResource("/$path").shouldNotBeNull() } }
    }
}
