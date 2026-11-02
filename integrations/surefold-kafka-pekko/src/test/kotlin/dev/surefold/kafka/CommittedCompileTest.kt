package dev.surefold.kafka

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBe
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** Which overload a call over `Committed` resolves to, and the runs it is refused, compiled for real. */
class CommittedCompileTest {

    @TempDir
    lateinit var workspace: File

    /** Both packages imported, as a service using both writes them, so a clash between them would show. */
    private val preamble = """
        import dev.surefold.fail.Either
        import dev.surefold.kafka.Committed
        import dev.surefold.kafka.DecodeError
        import dev.surefold.kafka.Kafka
        import dev.surefold.kafka.Topic
        import dev.surefold.kafka.absolve
        import dev.surefold.kafka.divertLefts
        import dev.surefold.kafka.filterRecord
        import dev.surefold.kafka.mapConcatRecord
        import dev.surefold.kafka.mapParRecord
        import dev.surefold.kafka.mapParRecordOrFail
        import dev.surefold.kafka.mapRecord
        import dev.surefold.kafka.mapRecordOrFail
        import dev.surefold.kafka.subscribe
        import dev.surefold.stream.Stream
        import dev.surefold.stream.absolve
        import dev.surefold.stream.divertLefts
        import dev.surefold.stream.filter
        import dev.surefold.stream.from
        import dev.surefold.stream.map
        import dev.surefold.stream.runCollect
        import dev.surefold.stream.runFold
        import dev.surefold.stream.runWith
        import org.apache.kafka.clients.consumer.ConsumerRecord
        import org.apache.pekko.kafka.ConsumerSettings
        import org.apache.pekko.stream.javadsl.Sink

        object Refused

        fun orders(settings: ConsumerSettings<String, String>): Stream<Nothing, Committed<ConsumerRecord<String, String>>> =
            Kafka.subscribe(settings, Topic("orders"))
    """.trimIndent()

    private fun compile(body: String): Pair<ExitCode, List<String>> =
        EmbeddedKotlin(workspace).compile("$preamble\n\n$body")

    @Test
    fun `every Record operator keeps the offset with no type arguments, beside surefold-stream's own`() {
        val (exit, errors) = compile(
            """
            fun pipeline(settings: ConsumerSettings<String, String>): Stream<Refused, Committed<Int>> {
                val values: Stream<Nothing, Committed<String>> = orders(settings).mapRecord { it.value() }
                val lengths: Stream<Nothing, Committed<Int>> = values.mapParRecord(2) { it.length }
                val kept: Stream<Nothing, Committed<Int>> = lengths.filterRecord { it > 0 }
                val twice: Stream<Nothing, Committed<Int>> = kept.mapConcatRecord { listOf(it, it) }
                val declared: Stream<Refused, Committed<Int>> = twice.mapRecordOrFail { if (it < 0) fail(Refused) else it }
                val parDeclared: Stream<Refused, Committed<Int>> =
                    twice.mapParRecordOrFail(2) { if (it < 0) fail(Refused) else it }
                val keeping: Stream<Refused, Committed<Int>> = declared.mapParRecord(2) { if (it < 0) fail(Refused) else it }
                return keeping.mapRecordOrFail { it }.mapParRecordOrFail(1) { it + parDeclared.hashCode() }
            }

            // surefold-stream's own, in the same file, on a stream that is not Kafka's.
            val plain: Stream<Nothing, Int> = Stream.from(listOf("a")).map { it.length }.filter { it > 0 }
            """.trimIndent(),
        )

        withClue("errors: $errors") { errors.shouldBeEmpty() }
        exit shouldBe ExitCode.OK
    }

    @Test
    fun `divertLefts and absolve over Committed resolve beside surefold-stream's own`() {
        val (exit, errors) = compile(
            """
            fun routed(s: Stream<Nothing, Committed<Either<DecodeError, String>>>): Stream<Nothing, Committed<String>> =
                s.divertLefts { error -> println(error.offset) }

            fun strict(s: Stream<Nothing, Committed<Either<DecodeError, String>>>): Stream<DecodeError, Committed<String>> =
                s.absolve()
            """.trimIndent(),
        )

        withClue("errors: $errors") { errors.shouldBeEmpty() }
        exit shouldBe ExitCode.OK
    }

    @Test
    fun `runCollect, runFold and runWith over Committed do not compile`() {
        listOf(
            "orders(settings).runCollect()",
            "orders(settings).runFold(0) { n, _ -> n + 1 }",
            "orders(settings).runWith(Sink.ignore())",
        ).forEach { run ->
            val (exit, errors) = compile("fun run(settings: ConsumerSettings<String, String>) = $run")
            withClue("`$run` compiled, and a stream of Committed must end on runCommitting") {
                exit shouldNotBe ExitCode.OK
            }
            errors.joinToString("\n") shouldContain "runCommitting"
        }
    }
}
