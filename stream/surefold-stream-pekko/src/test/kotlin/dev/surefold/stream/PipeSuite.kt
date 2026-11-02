package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeSameInstanceAs
import dev.surefold.junit.withClue
import org.apache.pekko.NotUsed
import org.apache.pekko.stream.javadsl.Flow
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import java.util.concurrent.CompletionStage

/** The middle of a pipeline as a value: built from a flow, spliced into a stream, composed, handed back. */
object PipeSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-pipe-test")

    private data class Row(val id: Int, val customer: String)

    private data class Customer(val id: Int, val name: String)

    private data class NoCustomer(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private val rows = listOf(Row(1, "ada"), Row(2, "grace"))

    private fun doubling(): Pipe<Nothing, Int, Int> = Pipe.from(Flow.create<Int>().map { n -> n * 2 })

    init {
        test("a pipe built from a Pekko flow runs through via and the run is Done") { pekko ->
            val customers: Pipe<Nothing, Row, Customer> =
                Pipe.from(Flow.of(Row::class.java).map { row -> Customer(row.id, row.customer) })

            val exit = Stream.from(rows).via(customers).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(Customer(1, "ada"), Customer(2, "grace")))
        }

        test("a pipe whose flow throws arrives at run as Exit Died carrying the cause") { pekko ->
            val cause = IllegalStateException("no ledger")
            val breaking: Pipe<Nothing, Row, Row> =
                Pipe.from(Flow.of(Row::class.java).map { row -> if (row.id == 2) throw cause else row })

            val exit = Stream.from(rows).via(breaking).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Died(cause)
        }

        // The pipe is in the middle, so the failure the source declared has to arrive as the one it declared.
        test("a failure declared upstream of a pipe still arrives as Exit Failed") { pekko ->
            val exit = Stream.from(rows)
                .mapOrFail { row -> if (row.id == 2) fail(NoCustomer(row.id)) else row.id }
                .via(doubling())
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoCustomer(2))
        }

        test("a pipe via a pipe is one pipe that does both") { pekko ->
            val quadrupling: Pipe<Nothing, Int, Int> = doubling().via(doubling())

            val exit = Stream.from(listOf(1, 2, 3)).via(quadrupling).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(4, 8, 12))
        }

        test("identity carries every element on unchanged") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .via(Pipe.identity<Int>())
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }

        test("toFlow hands back the flow the pipe was built from, and it runs under plain Pekko") { pekko ->
            val flow: Flow<Int, Int, NotUsed> = Flow.create<Int>().map { n -> n * 2 }

            val out: Flow<Int, Int, NotUsed> = Pipe.from(flow).toFlow()

            withClue("a pipe with nothing left to declare is the flow it was given") {
                out shouldBeSameInstanceAs flow
            }
            val elements = Source.from(listOf(1, 2, 3))
                .via(out)
                .runWith(Sink.seq(), pekko.system)
                .toCompletableFuture()
                .join()
            elements shouldBe listOf(2, 4, 6)
        }
    }
}
