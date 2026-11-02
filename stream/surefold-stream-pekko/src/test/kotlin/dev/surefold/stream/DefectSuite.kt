package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import org.apache.pekko.actor.testkit.typed.javadsl.LoggingTestKit
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage

/**
 * What a defect says and who hears it: the log a caller who reads nothing still gets, and the
 * operator, element and build site that ride with the cause.
 *
 * Its tests run one at a time: a `LoggingTestKit` expectation matches a log line that more than one
 * of them writes, so a neighbour's line could satisfy it, or be counted against it.
 */
object DefectSuite : SharedSuite<PekkoSystem>(concurrent = false) {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-defect-test")

    // The comments marking the calls whose lines a build site must name. No line holding one
    // of these constants ends with the marker, so the search below cannot find itself instead.
    private const val ASYNC_SITE = "// mapAsync built here"
    private const val REFUSED_SITE = "// failing mapAsync built here"
    private const val MAP_SITE = "// map built here"
    private const val PAR_SITE = "// mapPar built here"
    private const val PIPE_SITE = "// pipe built here"
    private const val CONCAT_SITE = "// mapConcat built here"
    private const val SEED_SITE = "// conflateWithSeed built here"

    private data class Row(val id: Int)

    private val rows = listOf(Row(1), Row(2))

    // A test's own causes, made fresh by each: the operator adds a suppressed exception to the cause it is handed,
    // and one cause shared by the whole suite would carry every earlier test's.
    private fun noLedger() = IllegalStateException("no ledger")

    private fun ledgerDown() = IllegalStateException("ledger down")

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** A ledger that answers null for the second row, where its own type says it will not. */
    @Suppress("UNCHECKED_CAST")
    private fun settle(row: Row): CompletionStage<Row> =
        if (row.id == 2) {
            CompletableFuture.completedFuture<Row?>(null) as CompletionStage<Row>
        } else {
            CompletableFuture.completedFuture(row)
        }

    /** A ledger that refuses the second row by failing its stage rather than by throwing. */
    private fun refuse(row: Row, ledgerDown: Throwable): CompletionStage<Row> =
        if (row.id == 2) {
            CompletableFuture.failedFuture(ledgerDown)
        } else {
            CompletableFuture.completedFuture(row)
        }

    private fun stamp(row: Row, noLedger: Throwable): Row = if (row.id == 2) throw noLedger else row

    /** The pipeline the log tests share, folded to a count so nothing holds an element. */
    private fun counted(): Run<Nothing, Int> =
        Stream.from(rows)
            .mapAsync(1) { row -> settle(row) } // mapAsync built here
            .runFold(0) { n, _ -> n + 1 }

    /** The same shape as [counted], for a stage that fails rather than one that completes with null. */
    private fun refused(ledgerDown: Throwable): Run<Nothing, Int> =
        Stream.from(rows)
            .mapAsync(1) { row -> refuse(row, ledgerDown) } // failing mapAsync built here
            .runFold(0) { n, _ -> n + 1 }

    /** The build hands the root over, so the file read here is the one this suite was compiled from. */
    private fun source(): File? {
        val root = System.getProperty("surefold.stream.repoRoot")
        withClue(
            "the build must pass -Dsurefold.stream.repoRoot; see surefold-stream-pekko/build.gradle.kts",
        ) {
            root.shouldNotBeNull()
        }
        return root?.let {
            File(
                it,
                "stream/surefold-stream-pekko/src/test/kotlin/dev/surefold/stream/DefectSuite.kt",
            )
        }
    }

    /**
     * Where a marked call sits, read out of this file rather than written down as a number that moves, checking
     * that it was found.
     */
    private fun siteOf(marker: String): String {
        val file = source()
        val index = file?.readLines()?.indexOfFirst { line -> line.trimEnd().endsWith(marker) } ?: -1
        withClue("no line of ${file?.name} ends with `$marker`") { index shouldBeGreaterThan -1 }
        return "${file?.name}:${index + 1}"
    }

    init {
        test("a defect reaches the system log though nobody reads the stage") { pekko ->
            val at = siteOf(ASYNC_SITE)

            LoggingTestKit.error("mapAsync died on Row(id=2), built at $at")
                .withCause(NullPointerException::class.java)
                .expect(pekko.typed) { counted().run(pekko.system) }
        }

        test("a null completion dies naming the operator, the element and the build site") { pekko ->
            val exit = counted().run(pekko.system).settled()
            val at = siteOf(ASYNC_SITE)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.shouldBeInstanceOf<NullPointerException>()
                died.cause.message shouldContain "mapAsync died on Row(id=2), built at $at"
            }
        }

        test("a stage that fails keeps its own exception, and the three facts ride with it") { pekko ->
            val ledgerDown = ledgerDown()
            val at = siteOf(REFUSED_SITE)

            val exit = LoggingTestKit.error("mapAsync died on Row(id=2), built at $at")
                .withCause(IllegalStateException::class.java)
                .expect(pekko.typed) { refused(ledgerDown).run(pekko.system).settled() }

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("a stage failed by the caller's own throwable hands back that throwable") {
                    died.cause shouldBeSameInstanceAs ledgerDown
                }
                died.cause.suppressed.single().message shouldContain "mapAsync died on Row(id=2), built at $at"
            }
        }

        test("a map that throws keeps its own exception, and the three facts ride with it") { pekko ->
            val noLedger = noLedger()
            val exit = Stream.from(rows)
                .map { row -> stamp(row, noLedger) } // map built here
                .runCollect()
                .run(pekko.system)
                .settled()
            val at = siteOf(MAP_SITE)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("wrapping the cause would lose the class the caller's own code threw") {
                    died.cause shouldBeSameInstanceAs noLedger
                }
                died.cause.suppressed.single().message shouldContain "map died on Row(id=2), built at $at"
            }
        }

        test("a mapPar body that throws keeps its own exception, and the three facts ride with it") { pekko ->
            val noLedger = noLedger()
            val exit = Stream.from(rows)
                // The type arguments are written out because a body that only throws names no failure,
                // and the `Stream<Nothing, A>` overload reads its `F` out of the body.
                .mapPar(1) { row -> stamp(row, noLedger) } // mapPar built here
                .runCollect()
                .run(pekko.system)
                .settled()
            val at = siteOf(PAR_SITE)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("a body's own throwable is what the stage is completed with") {
                    died.cause shouldBeSameInstanceAs noLedger
                }
                died.cause.suppressed.single().message shouldContain "mapPar died on Row(id=2), built at $at"
            }
        }

        test("a mapConcat that throws keeps its own exception, and the three facts ride with it") { pekko ->
            val noLedger = noLedger()
            val at = siteOf(CONCAT_SITE)

            val exit = LoggingTestKit.error("mapConcat died on Row(id=2), built at $at")
                .withCause(IllegalStateException::class.java)
                .expect(pekko.typed) {
                    Stream.from(rows)
                        .mapConcat { row -> listOf(stamp(row, noLedger)) } // mapConcat built here
                        .runCollect()
                        .run(pekko.system)
                        .settled()
                }

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                withClue("wrapping the cause would lose the class the caller's own code threw") {
                    died.cause shouldBeSameInstanceAs noLedger
                }
                died.cause.suppressed.single().message shouldContain "mapConcat died on Row(id=2), built at $at"
            }
        }

        // Which half of the operator sees the second row is the interpreter's business, so both throw.
        test("a conflateWithSeed that throws keeps its own exception, and the three facts ride with it") { pekko ->
            val noLedger = noLedger()
            val stamped = { row: Row -> stamp(row, noLedger) }
            val exit = Stream.from(rows)
                .conflateWithSeed({ row -> stamped(row) }, { _, row -> stamped(row) }) // conflateWithSeed built here
                .runCollect()
                .run(pekko.system)
                .settled()
            val at = siteOf(SEED_SITE)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                val facts = died.cause.suppressed.single().message
                died.cause shouldBeSameInstanceAs noLedger
                facts shouldContain "conflateWithSeed died on Row(id=2), built at $at"
            }
        }

        // A pipe is built where it is written, not where the stream it is spliced into is run.
        test("a pipe reports the site the pipe itself was built at") { pekko ->
            val ledger: Pipe<Nothing, Row, Row> = Pipe.mapAsync(1) { row -> settle(row) } // pipe built here

            val exit = Stream.from(rows).via(ledger).runCollect().run(pekko.system).settled()
            val at = siteOf(PIPE_SITE)

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                died.cause.message shouldContain "mapAsync died on Row(id=2), built at $at"
            }
        }
    }
}
