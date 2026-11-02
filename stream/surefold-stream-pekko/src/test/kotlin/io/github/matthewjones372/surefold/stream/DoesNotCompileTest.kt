package io.github.matthewjones372.surefold.stream

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The two lines the types are asked to hold, compiled: an element is never
 * null, and a declared failure stays in the type until something handles it.
 *
 * A claim about what does not compile is worth nothing written down — the
 * signature it depends on can change under it and nothing notices. So the
 * fixtures below are compiled for real and the compiler's own words asserted.
 */
class DoesNotCompileTest {

    @TempDir
    lateinit var workspace: File

    /** As [compile], for a fixture that is expected to be accepted. */
    private fun compiles(source: String): List<String> = EmbeddedKotlin(workspace).compile(source).second

    private fun compile(source: String): List<String> {
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)
        withClue("the fixture compiled, and this test exists because it must not") {
            exit shouldNotBe ExitCode.OK
        }
        return errors
    }

    private val preamble = """
        import io.github.matthewjones372.surefold.stream.Pipe
        import io.github.matthewjones372.surefold.stream.Stream
        import io.github.matthewjones372.surefold.stream.divertLefts
        import io.github.matthewjones372.surefold.stream.from
        import io.github.matthewjones372.surefold.stream.identity
        import io.github.matthewjones372.surefold.stream.map
        import io.github.matthewjones372.surefold.stream.mapAsync
        import io.github.matthewjones372.surefold.stream.mapConcat
        import io.github.matthewjones372.surefold.stream.mapOrFail
        import io.github.matthewjones372.surefold.stream.mapPar
        import io.github.matthewjones372.surefold.stream.mapParOrFail
        import io.github.matthewjones372.surefold.stream.single
        import io.github.matthewjones372.surefold.stream.toFlow
        import io.github.matthewjones372.surefold.stream.toSource
        import org.apache.pekko.stream.javadsl.Flow
        import org.apache.pekko.stream.javadsl.Sink
        import java.util.concurrent.CompletableFuture
        import java.util.concurrent.CompletionStage

        data class Row(val id: Int, val customer: String?)
        data class NoCustomer(val id: Int)
        data class Receipt(val id: Int)

        val rows = listOf(Row(1, "ada"))

        // A ledger whose answer is a receipt or nothing, which is the shape of
        // every lookup lifted into a future.
        fun settle(id: Int): CompletionStage<Receipt?> = CompletableFuture.completedFuture(null)
    """.trimIndent()

    @Test
    fun `a fold whose result may be null is refused by the exit bound`() {
        val errors = compile(
            "$preamble\nimport io.github.matthewjones372.surefold.stream.runFold\n" +
                "val broken = Stream.from(rows).runFold<Nothing, Row, String?>(null) { _, _ -> null }",
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Any"
        }
    }

    @Test
    fun `mapping to a nullable field is refused by the element bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).map { row -> row.customer }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Return type mismatch: expected 'Any', actual 'String?'."
        }
    }

    @Test
    fun `a mapOrFail body that can answer null is refused by the same bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).mapOrFail { row -> row.customer }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "Return type mismatch: expected 'Any', actual 'String?'."
        }
    }

    @Test
    fun `a stream still carrying a failure has no toSource to call`() {
        val errors = compile(
            """
            $preamble
            val failing = Stream.from(rows).mapOrFail { row -> row.customer ?: fail(NoCustomer(row.id)) }
            val escaped = failing.toSource()
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain "toSource"
        }
    }

    @Test
    fun `a pipe still carrying a failure has no toFlow to call`() {
        val errors = compile(
            """
            $preamble
            val failing: Pipe<NoCustomer, Row, Row> = Pipe.from(Flow.of(Row::class.java))
            val escaped = failing.toFlow()
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "None of the following candidates is applicable because of a receiver type mismatch:\n" +
                "fun <In, Out : Any> Pipe<Nothing, In, Out>.toFlow(): Flow<In, Out, NotUsed>"
        }
    }

    @Test
    fun `a mapAsync stage that can complete with null is refused by the element bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).mapAsync(1) { row -> settle(row.id) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'CompletionStage<uninferred B (of fun " +
                "<E, A : Any, B : Any> Stream<E, A>.mapAsync)>', actual 'CompletionStage<Receipt?>'."
        }
    }

    @Test
    fun `divertLefts has nothing to split on a stream whose elements are not Either`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).divertLefts(to = Sink.ignore<NoCustomer>())")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "None of the following candidates is applicable because of a receiver type mismatch:\n" +
                "fun <E, L : Any, R : Any> Stream<E, Either<L, R>>.divertLefts(to: Sink<L, *>): Stream<E, R>"
        }
    }

    @Test
    fun `single is refused the nullable that the element bound rules out`() {
        val errors = compile(
            """
            $preamble
            val name: String? = null
            val broken = Stream.single(name)
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Argument type mismatch: actual type is 'String?', but 'Any' was expected."
        }
    }

    @Test
    fun `from is refused an iterable of a nullable for the same reason`() {
        val errors = compile(
            """
            $preamble
            val name: String? = null
            val broken = Stream.from(listOf(name))
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "fun <A : Any> Stream.Companion.from(elements: Iterable<A>): Stream<Nothing, A>:\n" +
                "  Argument type mismatch: actual type is 'List<String?>', but " +
                "'Iterable<uninferred A (of fun <A : Any> Stream.Companion.from)>' was expected."
        }
    }

    @Test
    fun `mapConcat is refused an iterable of a nullable by the element bound`() {
        val errors = compile("$preamble\nval broken = Stream.from(rows).mapConcat { row -> listOf(row.customer) }")

        withClue(errors.joinToString("\n")) {
            errors.joinToString("\n") shouldContain
                "Return type mismatch: expected 'Iterable<uninferred B (of fun " +
                "<E, A : Any, B : Any> Stream<E, A>.mapConcat)>', actual 'List<String?>'."
        }
    }

    /** The other side of the claim: handle the null and the way out opens. */
    @Test
    fun `a stream with nothing left to fail with reaches toSource`() {
        val errors = compiles(
            """
            $preamble
            val handled = Stream.from(rows).map { row -> row.customer ?: "unknown" }
            val out = handled.toSource()
            """.trimIndent(),
        )

        withClue("if this ever stops compiling, the way out to Pekko is shut") {
            errors.shouldBeEmpty()
        }
    }

    @Test
    fun `a blocking body that never raises needs no failure type spelled out`() {
        val errors = compiles(
            """
            $preamble
            // No expected type on the call itself: one would hand inference the answer.
            val ids = Stream.from(rows).mapPar(2) { row -> row.id }
            val piped = Pipe.identity<Row>().mapPar(2) { row -> row.id }
            val stillCannotFail: Stream<Nothing, Int> = ids
            val norCanThis: Pipe<Nothing, Row, Int> = piped
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
        }
    }

    @Test
    fun `mapParOrFail reads the failure out of the body, on a stream that had none`() {
        val errors = compiles(
            """
            $preamble
            val named = Stream.from(rows).mapParOrFail(2) { row -> row.customer ?: fail(NoCustomer(row.id)) }
            val piped = Pipe.identity<Row>().mapParOrFail(2) { row -> row.customer ?: fail(NoCustomer(row.id)) }
            val declared: Stream<NoCustomer, String> = named
            val pipeDeclared: Pipe<NoCustomer, Row, String> = piped
            """.trimIndent(),
        )

        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
        }
    }
}
