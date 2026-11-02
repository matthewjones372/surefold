package io.github.matthewjones372.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import org.apache.pekko.Done
import org.apache.pekko.stream.javadsl.Sink
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A run to the sink the caller names, beside the sink `divertLefts` names, in one materialisation. */
object RunWithSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-runwith-test")

    private data class Receipt(val id: Int)

    private data class Declined(val id: Int)

    private data class NoLedger(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    /** Whether the stage answered at all: a sink that was heard failed with the run rather than hanging. */
    private fun <T> CompletionStage<T>.answered(): Boolean =
        handle { _, _ -> true }.toCompletableFuture().get(30, TimeUnit.SECONDS)

    private fun completedStage(id: Int): CompletionStage<Int> = CompletableFuture.completedFuture(id)

    private fun accepted(id: Int): Either<Declined, Receipt> = Either.Right(Receipt(id))

    private fun declined(id: Int): Either<Declined, Receipt> = Either.Left(Declined(id))

    init {
        test("lefts reach the sink divertLefts names and rights the sink the run names") { pekko ->
            val declines = ConcurrentLinkedQueue<Declined>()
            val receipts = ConcurrentLinkedQueue<Receipt>()
            val bothDeclines = CountDownLatch(2)

            val exit = Stream.from(listOf(accepted(1), declined(2), accepted(3), declined(4)))
                .divertLefts(
                    to = Sink.foreach<Declined> { declined ->
                        declines.add(declined)
                        bothDeclines.countDown()
                    },
                )
                .runWith(Sink.foreach<Receipt> { receipt -> receipts.add(receipt) })
                .run(pekko.system)
                .settled()

            withClue("the sink's materialised value is the run's, and Sink.foreach materialises Pekko's Done") {
                exit shouldBe Exit.Done(Done.getInstance())
            }
            receipts.toList() shouldBe listOf(Receipt(1), Receipt(3))
            withClue("the diverted branch is a branch of its own and can outlive the run") {
                bothDeclines.await(30, TimeUnit.SECONDS) shouldBe true
            }
            declines.toList() shouldBe listOf(Declined(2), Declined(4))
        }

        test("Sink seq through runWith answers what runCollect answers") { pekko ->
            val elements = listOf("a", "b", "c")

            val toTheNamedSink = Stream.from(elements).runWith(Sink.seq<String>()).run(pekko.system).settled()
            val toTheOneWithAName = Stream.from(elements).runCollect().run(pekko.system).settled()

            toTheNamedSink shouldBe Exit.Done(elements)
            withClue("runCollect is this call with the sink written out, so the two cannot disagree") {
                toTheNamedSink shouldBe toTheOneWithAName
            }
        }

        test("Sink ignore answers Done") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3)).runWith(Sink.ignore<Int>()).run(pekko.system).settled()

            exit shouldBe Exit.Done(Done.getInstance())
        }

        test("a failure after both sinks have taken elements is Exit Failed and the left sink still answers") { pekko ->
            val declines = ConcurrentLinkedQueue<Declined>()
            val receipts = ConcurrentLinkedQueue<Receipt>()
            val declineTaken = CompletableFuture<Declined>()
            // Pre-materialised only to hold the left sink's stage: divertTo drops it, and whether that
            // branch was heard is what this test is about.
            val diverted = Sink.foreach<Declined> { declined ->
                declines.add(declined)
                declineTaken.complete(declined)
            }.preMaterialize(pekko.system)

            val exit = Stream.from(listOf(1, 2, 3))
                // The element that fails waits for the diverted branch rather than racing it, so that
                // "after both sinks have taken elements" is the order this run is in. The wait is a
                // stage, because a body that blocked here would hold the thread the sinks run on.
                .mapAsync(1) { id ->
                    if (id == 3) declineTaken.orTimeout(30, TimeUnit.SECONDS).thenApply { id } else completedStage(id)
                }
                .mapOrFail { id ->
                    when (id) {
                        1 -> accepted(id)
                        2 -> declined(id)
                        else -> fail(NoLedger(id))
                    }
                }
                .divertLefts(to = diverted.second())
                .runWith(Sink.foreach<Receipt> { receipt -> receipts.add(receipt) })
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(NoLedger(3))
            receipts.toList() shouldBe listOf(Receipt(1))
            withClue("no sink is left half-written unheard: the diverted branch ended with the run") {
                diverted.first().answered() shouldBe true
            }
            declines.toList() shouldBe listOf(Declined(2))
        }

        test("a throw on the way to the sink is Exit Died carrying the cause") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2, 3))
                .map { id -> if (id == 2) throw cause else Receipt(id) }
                .runWith(Sink.ignore<Receipt>())
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }
    }
}
