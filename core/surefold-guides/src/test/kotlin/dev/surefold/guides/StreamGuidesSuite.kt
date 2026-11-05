package dev.surefold.guides

import dev.surefold.TestClock
import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.scope
import dev.surefold.stream.Actors
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.TestStreams
import dev.surefold.stream.absolve
import dev.surefold.stream.filter
import dev.surefold.stream.from
import dev.surefold.stream.mapAsync
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import dev.surefold.stream.runFold
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The examples in `docs/stream.md` and the README's quick start, each compiled on its own against the core's stream
 * modules alone (Forks, Actors and TestStreams), and the page's two pipelines run as it shows them.
 */
class StreamGuidesSuite {

    private val document =
        listOf(
            "backend-example",
            "mappar-example",
            "unordered-example",
            "limit-example",
            "bytes-example",
            "missing-example",
            "readme-example",
        )

    @TempDir lateinit var workspace: File

    private fun page(path: String): String {
        val root = System.getProperty("surefold.guides.repoRoot")
        withClue("the build must pass -Dsurefold.guides.repoRoot; see core/surefold-guides/build.gradle.kts") {
            root.shouldNotBeNull()
        }
        return File(root!!, path).readText()
    }

    private fun marked(path: String, marker: String): List<String> =
        Regex("""<!-- $marker -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
            .findAll(page(path))
            .map { it.groupValues[1] }
            .toList()

    private fun errors(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(File(workspace, "${source.hashCode()}").apply { mkdirs() }).compile(source)
        return errors + listOfNotNull(if (exit == ExitCode.OK) null else "exit $exit")
    }

    @Test
    fun `every example the stream guide marks compiles against the core alone`() {
        document.forEach { marker ->
            val fences = marked("docs/stream.md", marker)
            withClue("docs/stream.md must hold one kotlin fence marked <!-- $marker -->") { fences.size shouldBe 1 }
            withClue("docs/stream.md $marker") { errors(fences.single()).shouldBeEmpty() }
        }
    }

    @Test
    fun `the README's quick start compiles against the core alone`() {
        val fences = marked("README.md", "quickstart-example")
        withClue("README.md must hold one kotlin fence marked <!-- quickstart-example -->") { fences.size shouldBe 1 }
        errors(fences.single()).shouldBeEmpty()
    }

    @Test
    fun `an example that no longer matches the library fails to compile`() {
        val backends = marked("docs/stream.md", "backend-example").single()
        errors(backends.replace("runFold(", "runFolded(")).shouldNotBeEmpty()
    }

    private data class Order(val id: Int, val pence: Long?)

    private data class Unpriced(val id: Int)

    private val takings: Run<Unpriced, Long> =
        Stream.from(listOf(Order(1, 1200), Order(2, 800), Order(3, 4500)))
            .mapOrFail { order -> order.pence ?: fail(Unpriced(order.id)) }
            .filter { pence -> pence >= 1000 }
            .runFold(0L) { total, pence -> total + pence }

    private fun <E, R : Any> CompletionStage<Exit<E, R>>.exit(): Exit<E, R> =
        toCompletableFuture().get(30, TimeUnit.SECONDS)

    // The description the page shows, line for line, run: on every backend it names it answers the same.
    @Test
    fun `the guide's description answers the same on Forks, TestStreams and Actors`() {
        val onForks = takings.run(Forks()).exit()
        val inATest = takings.run(TestStreams(TestClock())).exit()
        val onActors = scope { takings.run(Actors(this)).exit() }

        listOf(onForks, inATest, onActors).forEach { it shouldBe Exit.Done(5_700L) }
    }

    private data class Row(val id: Int, val customer: String?)

    private data class Customer(val id: Int, val name: String)

    private data class Receipt(val id: Int)

    private sealed interface IngestError

    private data class NoCustomer(val id: Int) : IngestError

    private data class Declined(val id: Int) : IngestError

    private fun settled(rows: List<Row>, declining: Set<Int> = emptySet()) =
        Stream.from(rows)
            .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
            .mapAsync(4) { customer ->
                val settled: Either<Declined, Receipt> =
                    if (customer.id in declining) Either.Left(Declined(customer.id))
                    else Either.Right(Receipt(customer.id))
                CompletableFuture.completedFuture(settled)
            }
            .absolve()
            .runCollect()
            .run(Forks())
            .exit()

    // The pipeline the page shows, run: a missing customer and a decline each stop it with their own name.
    @Test
    fun `the guide's pipeline collects the receipts, and stops at the first row it cannot settle`() {
        val rows = listOf(Row(1, "ada"), Row(2, "grace"), Row(3, null))

        settled(rows.take(2)) shouldBe Exit.Done(listOf(Receipt(1), Receipt(2)))
        settled(rows) shouldBe Exit.Failed(NoCustomer(3))
        settled(rows.take(2), declining = setOf(2)) shouldBe Exit.Failed(Declined(2))
    }
}
