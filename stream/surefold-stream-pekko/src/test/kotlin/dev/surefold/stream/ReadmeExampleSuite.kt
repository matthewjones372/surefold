package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import org.apache.pekko.Done
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.javadsl.Sink
import org.jetbrains.kotlin.cli.common.ExitCode
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The examples in `docs/stream.md`, compiled out of the document and then run
 * beside it: the fences marked `<!-- ... -->`, because a marker renders as
 * nothing on the page and survives the heading above it being reworded.
 *
 * The README's quick start is compiled here too, since this module's test classpath holds the core it uses.
 */
object ReadmeExampleSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-readme-test")

    private const val MARKER = "<!-- readme-example -->"

    /** The three fences of the before-and-after: the fixtures, and the pipeline written each way. */
    private const val FIXTURES = "<!-- example-fixtures -->"
    private const val BEFORE = "<!-- before-example -->"
    private const val AFTER = "<!-- after-example -->"

    /** The four lines that say what absence is, on both pages. */
    private const val MISSING = "<!-- missing-example -->"

    /** The one that only the document carries, since the README sends the reader there for it. */
    private const val BLOCKING = "<!-- mappar-example -->"

    /** The unordered window, which only the document shows (spec 0170). */
    private const val UNORDERED = "<!-- unordered-example -->"

    /** A stream under an adaptive limit (spec 0171). */
    private const val LIMIT = "<!-- limit-example -->"

    /** Bytes read from an InputStream and written to an OutputStream (spec 0249). */
    private const val BYTES = "<!-- bytes-example -->"

    /** One description, started on Pekko, Forks and TestStreams. */
    private const val BACKENDS = "<!-- backend-example -->"

    /** The README's complete program, on the core alone. */
    private const val QUICKSTART = "<!-- quickstart-example -->"

    private val inTheDocument =
        listOf(MARKER, FIXTURES, BEFORE, AFTER, MISSING, BLOCKING, UNORDERED, LIMIT, BYTES, BACKENDS)

    private fun marked(marker: String) = Regex("""$marker\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    /** The build hands the root over, so the pages read here are the ones the repository ships. */
    private fun page(name: String, then: (File) -> Unit) {
        val root: String? = System.getProperty("surefold.stream.repoRoot")
        return withClue("the build must pass -Dsurefold.stream.repoRoot; see surefold-stream-pekko/build.gradle.kts") {
            root.shouldNotBeNull { path -> then(File(path, name)) }
        }
    }

    private fun document(then: (File) -> Unit) = page("docs/stream.md", then)

    private fun readme(then: (File) -> Unit) = page("README.md", then)

    private fun examples(page: File, marker: String): List<String> =
        marked(marker).findAll(page.readText()).map { match -> match.groupValues[1] }.toList()

    private fun marksOne(page: File, marker: String) =
        withClue("$page must hold one kotlin fence marked $marker") {
            examples(page, marker).size shouldBe 1
        }

    /** The one fence [page] marks with [marker], checked by [then] only if there is exactly one. */
    private fun only(page: File, marker: String, then: (String) -> Unit) {
        marksOne(page, marker)
        examples(page, marker).singleOrNull()?.let(then)
    }

    /** Compiled in a workspace of its own, as `@TempDir` gave each test, and removed after. */
    private fun compiles(source: String) {
        val (exit, errors) = withTempDirectory("surefold-stream-readme") { workspace ->
            EmbeddedKotlin(workspace.toFile()).compile(source)
        }

        return withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
    }

    private data class Row(val id: Int, val customer: String?)

    private data class Customer(val id: Int, val name: String)

    private data class Receipt(val id: Int)

    private sealed interface IngestError

    private data class NoCustomer(val id: Int) : IngestError

    private data class Declined(val id: Int) : IngestError

    /** The document's ledger, declining the even ids so that the twin runs both branches. */
    private class Ledger {
        fun settle(customer: Customer): CompletionStage<Either<Declined, Receipt>> =
            if (customer.id % 2 == 0) {
                CompletableFuture.completedFuture(Either.Left(Declined(customer.id)))
            } else {
                CompletableFuture.completedFuture(Either.Right(Receipt(customer.id)))
            }
    }

    private val ledger = Ledger()

    private val rows = listOf(
        Row(1, "ada"),
        Row(2, "grace"),
        Row(3, "alan"),
        Row(4, "edsger"),
        Row(5, "barbara"),
    )
    init {
        test("the document marks one fence for each example it compiles") { _ ->
            document { stream -> inTheDocument.forAll { marker -> marksOne(stream, marker) } }
        }

        test("the document's description runs on every backend, and compiles against all three") { _ ->
            document { stream -> only(stream, BACKENDS) { compiles(it) } }
        }

        test("the README's quick start compiles against the core") { _ ->
            readme { readme -> only(readme, QUICKSTART) { compiles(it) } }
        }

        test("the example the document shows compiles against the library") { _ ->
            document { stream -> only(stream, MARKER) { compiles(it) } }
        }

        // Both halves of the before-and-after, each on the fixtures the section states once.
        test("the pipeline written each way compiles against what it is written on") { _ ->
            document { stream ->
                only(stream, FIXTURES) { fixtures ->
                    only(stream, BEFORE) { before -> compiles("$fixtures\n\n$before") }
                    only(stream, AFTER) { after -> compiles("$fixtures\n\n$after") }
                }
            }
        }

        test("the builders that name an absence compile as the document shows them") { _ ->
            document { stream -> only(stream, MISSING) { compiles(it) } }
        }

        test("the blocking body the document shows compiles") { _ ->
            document { stream -> only(stream, BLOCKING) { compiles(it) } }
        }

        test("the unordered window the document shows compiles") { _ ->
            document { stream -> only(stream, UNORDERED) { compiles(it) } }
        }

        test("the limited stream the document shows compiles") { _ ->
            document { stream -> only(stream, LIMIT) { compiles(it) } }
        }

        test("the bytes the document reads and writes compile") { _ ->
            document { stream -> only(stream, BYTES) { compiles(it) } }
        }

        // The same pipeline, line for line, run: what the document shows is compiled and also true.
        test("the pipeline the document shows sends the receipts and diverts the declines") { pekko ->
            val system: ClassicActorSystemProvider = pekko.system
            val declines = ConcurrentLinkedQueue<Declined>()
            val bothDeclines = CountDownLatch(2)
            val declinedSink = Sink.foreach<Declined> { declined ->
                declines.add(declined)
                bothDeclines.countDown()
            }
            val receipts = ConcurrentLinkedQueue<Receipt>()
            val receiptSink = Sink.foreach<Receipt> { receipt -> receipts.add(receipt) }

            val settled: Either<IngestError, Done> = attempt {
                awaitExit(
                    Stream.from(rows)
                        .mapOrFail { row -> Customer(row.id, row.customer ?: fail(NoCustomer(row.id))) }
                        .mapAsync(4) { customer -> ledger.settle(customer) }
                        .divertLefts(to = declinedSink)
                        .runWith(receiptSink)
                        .run(system),
                )
            }

            settled shouldBe Done.getInstance().right()
            withClue("the run ends when its own sink does, so every receipt has been sent by now") {
                receipts.toList() shouldBe listOf(Receipt(1), Receipt(3), Receipt(5))
            }
            withClue("the diverted branch is a branch of its own and can outlive the run") {
                bothDeclines.await(30, TimeUnit.SECONDS) shouldBe true
            }
            declines.toList() shouldBe listOf(Declined(2), Declined(4))
        }
    }
}
