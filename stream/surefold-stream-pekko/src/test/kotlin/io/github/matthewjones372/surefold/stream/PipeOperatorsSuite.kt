package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Every operator on a pipe: the answer its `Stream` twin gives, and one pipe spliced into two sources. */
object PipeOperatorsSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-pipe-operators-test")

    /** Long enough that a machine under load does not fail a claim about a branch being heard. */
    private const val GENEROUS_SECONDS = 30L

    private data class Row(val id: Int, val customer: String?)

    private data class Customer(val id: Int, val name: String)

    private data class Receipt(val id: Int)

    private sealed interface IngestError

    private data class NoCustomer(val id: Int) : IngestError

    private data class Declined(val id: Int) : IngestError

    /** A ledger that answers where it stands, so a body has something of its own to do. */
    private fun settle(customer: Customer): Either<Declined, Receipt> =
        if (customer.id % 2 == 0) Either.Left(Declined(customer.id)) else Either.Right(Receipt(customer.id))

    private fun accepted(id: Int): Either<Declined, Receipt> = Either.Right(Receipt(id))

    private fun rejected(id: Int): Either<Declined, Receipt> = Either.Left(Declined(id))

    private fun completed(n: Int): CompletionStage<Int> = CompletableFuture.completedFuture(n)

    /** The second element fails, so every twin below has both a value and a failure to answer for. */
    private fun Fail<Declined>.failingAt(n: Int): Int = if (n == 2) fail(Declined(n)) else n

    private fun recovering(declined: Declined): Stream<Nothing, Int> = Stream.from(listOf(declined.id * 100))

    private fun nine(): Stream<Nothing, Int> = Stream.from(listOf(9))

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun <E, A : Any> Stream<E, A>.collected(pekko: PekkoSystem): Exit<E, List<A>> =
        runCollect().run(pekko.system).settled()

    private val ids = listOf(1, 2, 3)

    private val eithers = listOf(accepted(1), rejected(2), accepted(3))

    /** The spec's pipe: a customer named or the row's failure, the ledger asked, the declines diverted. */
    private fun settling(declines: Sink<Declined, *>): Pipe<IngestError, Row, Receipt> =
        Pipe.mapOrFail<IngestError, Row, Customer> { row ->
            Customer(row.id, row.customer ?: fail(NoCustomer(row.id)))
        }
            .mapPar(4) { customer -> settle(customer) }
            .divertLefts(to = declines)

    init {
        test("one pipe settles two sources, and each run answers for its own") { pekko ->
            val declines = ConcurrentLinkedQueue<Declined>()
            val bothDeclines = CountDownLatch(2)
            val settling = settling(
                Sink.foreach { declined: Declined ->
                    declines.add(declined)
                    bothDeclines.countDown()
                },
            )

            val fromRows =
                Stream.from(listOf(Row(1, "ada"), Row(2, "grace"), Row(3, "alan"))).via(settling).collected(pekko)
            val fromMore = Stream.from(listOf(Row(4, "edsger"), Row(5, "barbara"))).via(settling).collected(pekko)

            fromRows shouldBe Exit.Done(listOf(Receipt(1), Receipt(3)))
            fromMore shouldBe Exit.Done(listOf(Receipt(5)))
            withClue("the diverted branch is a branch of its own and can outlive the run that fed it") {
                bothDeclines.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
            }
            declines.toList() shouldContainExactlyInAnyOrder listOf(Declined(2), Declined(4))
        }

        test("the failure a pipe declares is the one the stream it is spliced into ends with") { pekko ->
            val exit = Stream.from(listOf(Row(1, "ada"), Row(2, null))).via(settling(Sink.ignore())).collected(pekko)

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        // One implementation each, so the two spellings of an operator cannot answer differently.
        test("each operator answers through a pipe what it answers on the stream") { pekko ->
            val twins = listOf(
                mapTwin(pekko),
                filterTwin(pekko),
                mapOrFailTwin(pekko),
                mapAsyncTwin(pekko),
                mapParTwin(pekko),
                eitherTwin(pekko),
                absolveTwin(pekko),
                divertLeftsTwin(pekko),
                catchAllTwin(pekko),
                orElseTwin(pekko),
                conflateWithSeedTwin(pekko),
                mapConcatTwin(pekko),
            )

            twins.forEach { twin -> withClue(twin.operator) { twin.viaPipe shouldBe twin.onStream } }
        }

        test("a pipe chains the element operators and the stream it is spliced into runs them in order") { pekko ->
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ledger-1") }
            val threads = ConcurrentLinkedQueue<String>()
            val pipe: Pipe<Nothing, Int, Int> = Pipe.identity<Int>()
                .map { n -> n * 2 }
                .filter { n -> n != 4 }
                .mapAsync(2) { n -> completed(n) }
                .mapPar(2, on = executor) { n ->
                    threads.add(Thread.currentThread().name)
                    n + 1
                }

            val exit = try {
                Stream.from(ids).via(pipe).collected(pekko)
            } finally {
                executor.shutdown()
            }

            exit shouldBe Exit.Done(listOf(3, 7))
            withClue("a body runs where the call said it would, on a pipe as on a stream") {
                threads.toList() shouldBe listOf("ledger-1", "ledger-1")
            }
        }

        // The shape the socket that asked for both wrote: a backlog coalesced, and flattened again.
        test("a pipe collapses a backlog by cart and flattens it again") { pekko ->
            val coalesced: Pipe<Nothing, Int, Int> = Pipe.identity<Int>()
                .conflateWithSeed({ n -> setOf(n) }, { seen, n -> seen + n })
                .mapConcat { seen -> seen }

            Stream.from(ids).via(coalesced).collected(pekko) shouldBe Exit.Done(listOf(1, 2, 3))
        }

        // A pipe with no failure named yet reads one out of the body it is given, as `Stream.from` does.
        test("identity names its failure from the first body that can fail") { pekko ->
            val forked: Pipe<Declined, Int, Int> = Pipe.identity<Int>().mapParOrFail(2) { n -> failingAt(n) }
            val mapped: Pipe<Declined, Int, Int> = Pipe.identity<Int>().mapOrFail { n -> failingAt(n) }

            Stream.from(ids).via(forked).collected(pekko) shouldBe Exit.Failed(Declined(2))
            Stream.from(ids).via(mapped).collected(pekko) shouldBe Exit.Failed(Declined(2))
        }

        test("a pipe handles the failure it declared, and what it hands on has none left") { pekko ->
            val handled: Pipe<Nothing, Int, Int> =
                Pipe.mapOrFail<Declined, Int, Int> { n -> failingAt(n) }
                    .map { n -> n * 10 }
                    .catchAll { declined -> recovering(declined) }

            val exit = Stream.from(ids).via(handled).collected(pekko)

            exit shouldBe Exit.Done(listOf(10, 200))
        }

        // The executor is the pipe's to name whether or not it has already said what it fails with.
        test("a pipe that has named its failure still runs its bodies where the call says") { pekko ->
            val executor = Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "ledger-2") }
            val threads = ConcurrentLinkedQueue<String>()
            val paid: Pipe<Declined, Int, Int> = Pipe.mapOrFail<Declined, Int, Int> { n -> failingAt(n) }
                .mapPar(2, on = executor) { n ->
                    threads.add(Thread.currentThread().name)
                    n * 10
                }

            val exit = try {
                Stream.from(listOf(1, 3)).via(paid).collected(pekko)
            } finally {
                executor.shutdown()
            }

            exit shouldBe Exit.Done(listOf(10, 30))
            threads.toList() shouldBe listOf("ledger-2", "ledger-2")
        }

        test("either and orElse and absolve chain on a pipe as they do on a stream") { pekko ->
            val asElements: Pipe<Nothing, Int, Either<Declined, Int>> =
                Pipe.mapOrFail<Declined, Int, Int> { n -> failingAt(n) }.either()
            val otherwise: Pipe<Declined, Int, Int> =
                Pipe.mapOrFail<Declined, Int, Int> { n -> failingAt(n) }.orElse(nine())
            val backAgain: Pipe<Declined, Int, Receipt> =
                Pipe.map<Int, Either<Declined, Receipt>> { n -> if (n == 2) rejected(n) else accepted(n) }.absolve()

            Stream.from(ids).via(asElements).collected(pekko) shouldBe
                Exit.Done(listOf(Either.Right(1), Either.Left(Declined(2))))
            Stream.from(ids).via(otherwise).collected(pekko) shouldBe Exit.Done(listOf(1, 9))
            Stream.from(ids).via(backAgain).collected(pekko) shouldBe Exit.Failed(Declined(2))
        }
    }

    /** One operator, run twice: written on the stream, and as the pipe the stream now goes through. */
    private data class Twin(val operator: String, val onStream: Exit<*, *>, val viaPipe: Exit<*, *>)

    private fun mapTwin(pekko: PekkoSystem): Twin = Twin(
        "map",
        Stream.from(ids).map { n -> n * 2 }.collected(pekko),
        Stream.from(ids).via(Pipe.map { n: Int -> n * 2 }).collected(pekko),
    )

    private fun filterTwin(pekko: PekkoSystem): Twin = Twin(
        "filter",
        Stream.from(ids).filter { n -> n != 2 }.collected(pekko),
        Stream.from(ids).via(Pipe.filter { n: Int -> n != 2 }).collected(pekko),
    )

    private fun mapOrFailTwin(pekko: PekkoSystem): Twin = Twin(
        "mapOrFail",
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.collected(pekko),
        Stream.from(ids).via(Pipe.mapOrFail<Declined, Int, Int> { n -> failingAt(n) }).collected(pekko),
    )

    private fun mapAsyncTwin(pekko: PekkoSystem): Twin = Twin(
        "mapAsync",
        Stream.from(ids).mapAsync(2) { n -> completed(n) }.collected(pekko),
        Stream.from(ids).via(Pipe.mapAsync<Int, Int>(2) { n -> completed(n) }).collected(pekko),
    )

    private fun mapParTwin(pekko: PekkoSystem): Twin = Twin(
        "mapPar",
        Stream.from(ids).mapParOrFail(2) { n -> failingAt(n) }.collected(pekko),
        Stream.from(ids).via(Pipe.mapPar<Declined, Int, Int>(2) { n -> failingAt(n) }).collected(pekko),
    )

    private fun eitherTwin(pekko: PekkoSystem): Twin = Twin(
        "either",
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.either().collected(pekko),
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.via(Pipe.either<Declined, Int>()).collected(pekko),
    )

    private fun absolveTwin(pekko: PekkoSystem): Twin = Twin(
        "absolve",
        Stream.from(eithers).absolve().collected(pekko),
        Stream.from(eithers).via(Pipe.absolve<Declined, Declined, Receipt>()).collected(pekko),
    )

    private fun divertLeftsTwin(pekko: PekkoSystem): Twin = Twin(
        "divertLefts",
        Stream.from(eithers).divertLefts(to = Sink.ignore()).collected(pekko),
        Stream.from(eithers).via(Pipe.divertLefts<Declined, Receipt>(to = Sink.ignore())).collected(pekko),
    )

    private fun catchAllTwin(pekko: PekkoSystem): Twin = Twin(
        "catchAll",
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.catchAll(::recovering).collected(pekko),
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.via(Pipe.catchAll(::recovering)).collected(pekko),
    )

    private fun orElseTwin(pekko: PekkoSystem): Twin = Twin(
        "orElse",
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.orElse(nine()).collected(pekko),
        Stream.from(ids).mapOrFail { n -> failingAt(n) }.via(Pipe.orElse(nine())).collected(pekko),
    )

    /** Flattened again, so what is asserted is what came through rather than which burst it came in. */
    private fun conflateWithSeedTwin(pekko: PekkoSystem): Twin = Twin(
        "conflateWithSeed",
        Stream.from(ids).conflateWithSeed({ n -> setOf(n) }, { seen, n -> seen + n }).mapConcat { seen -> seen }
            .collected(pekko),
        Stream.from(ids).via(Pipe.conflateWithSeed({ n: Int -> setOf(n) }, { seen, n -> seen + n }))
            .mapConcat { seen -> seen }
            .collected(pekko),
    )

    private fun mapConcatTwin(pekko: PekkoSystem): Twin = Twin(
        "mapConcat",
        Stream.from(ids).mapConcat { n -> listOf(n, n * 10) }.collected(pekko),
        Stream.from(ids).via(Pipe.mapConcat { n: Int -> listOf(n, n * 10) }).collected(pekko),
    )
}
