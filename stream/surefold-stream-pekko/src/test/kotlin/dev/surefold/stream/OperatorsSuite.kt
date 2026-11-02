package dev.surefold.stream

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.withClue
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.testkit.javadsl.TestSink
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** What the asynchronous and splitting operators promise, asserted on a real system. */
object OperatorsSuite : SharedSuite<PekkoSystem>() {
    override val shared: Resource<PekkoSystem> = pekkoSystem("surefold-stream-operators-test")

    private data class Receipt(val id: Int)

    private data class Declined(val id: Int)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> = toCompletableFuture().join()

    private fun settle(id: Int): CompletionStage<Receipt> = CompletableFuture.completedFuture(Receipt(id))

    /** A ledger that answers null where its own type says it will not: the completion Pekko drops. */
    @Suppress("UNCHECKED_CAST")
    private fun nothingAtAll(): CompletionStage<Receipt> =
        CompletableFuture.completedFuture<Receipt?>(null) as CompletionStage<Receipt>

    private fun accepted(id: Int): Either<Declined, Receipt> = Either.Right(Receipt(id))

    private fun declined(id: Int): Either<Declined, Receipt> = Either.Left(Declined(id))

    init {
        test("mapAsync answers in the order of its input though the stages complete backwards") { pekko ->
            val gates = (1..4).associateWith { CompletableFuture<Receipt>() }
            gates.keys.reversed().forEach { id -> gates.getValue(id).complete(Receipt(id)) }

            val exit = Stream.from(gates.keys.toList())
                .mapAsync(4) { id -> gates.getValue(id) }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(2), Receipt(3), Receipt(4)))
        }

        test("a stage that completes with null dies rather than dropping the element") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapAsync(1) { id -> if (id == 2) nothingAtAll() else settle(id) }
                .runCollect()
                .run(pekko.system)
                .settled()

            withClue("Pekko would have answered Done with the other two elements") {
                exit.shouldBeInstanceOf<Exit.Died> { died ->
                    died.cause.shouldBeInstanceOf<NullPointerException>()
                    withClue("a defect that does not name where it came from is the disappearance again") {
                        died.cause.message shouldContain "mapAsync"
                    }
                }
            }
        }

        test("a stage that fails arrives as Exit Died carrying what failed it") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2, 3))
                .mapAsync(2) { id ->
                    if (id == 2) CompletableFuture.failedFuture<Receipt>(cause) else settle(id)
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }

        test("a declared failure before mapAsync still arrives as Exit Failed") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 2) fail(Declined(id)) else id }
                .mapAsync(2) { id -> settle(id) }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(2))
        }

        test("divertLefts counts every Left at the sink and carries every Right on") { pekko ->
            val counted = ConcurrentLinkedQueue<Declined>()
            val allThree = CountDownLatch(3)

            val exit = Stream.from(listOf(accepted(1), declined(2), declined(3), accepted(4), declined(5)))
                .divertLefts(
                    to = Sink.foreach { declined: Declined ->
                        counted.add(declined)
                        allThree.countDown()
                    },
                )
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(4)))
            withClue("the sink is a branch of its own, so the run can finish before it has seen the last Left") {
                allThree.await(30, TimeUnit.SECONDS) shouldBe true
            }
            counted.toList() shouldBe listOf(Declined(2), Declined(3), Declined(5))
        }

        test("a declared failure upstream of divertLefts is still Exit Failed") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 2) fail(Declined(id)) else accepted(id) }
                .divertLefts(to = Sink.ignore())
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(2))
        }

        test("either makes the failure the last element and leaves nothing for the type to carry") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
                .either()
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(
                listOf(Either.Right(Receipt(1)), Either.Right(Receipt(2)), Either.Left(Declined(3))),
            )
        }

        test("a defect is not a failure either can name, so it still dies") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2, 3))
                .map { id -> if (id == 2) throw cause else Receipt(id) }
                .either()
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }

        test("absolve fails on the first Left and never reaches what follows it") { pekko ->
            val seen = ConcurrentLinkedQueue<Receipt>()

            val exit = Stream.from(listOf(accepted(1), declined(2), accepted(3)))
                .absolve()
                .map { receipt ->
                    seen.add(receipt)
                    receipt
                }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(2))
            withClue("the Right after the Left must never have been looked at") {
                seen.toList() shouldBe listOf(Receipt(1))
            }
        }

        test("absolve after either gives back the failure it made an element of") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
                .either()
                .absolve()
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(3))
        }

        test("catchAll sees the declared failure and its stream carries on from there") { pekko ->
            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
                .catchAll { declined -> Stream.from(listOf(Receipt(declined.id * 10))) }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(2), Receipt(30)))
        }

        test("catchAll does not catch a defect") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.from(listOf(1, 2, 3))
                .mapOrFail { id -> if (id == 3) fail(Declined(id)) else Receipt(id) }
                .map { receipt -> if (receipt.id == 2) throw cause else receipt }
                .catchAll { declined -> Stream.from(listOf(Receipt(declined.id * 10))) }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }

        // With the failure handled there is nothing left to declare, so the way out to Pekko opens.
        test("a stream whose failure catchAll handled reaches toSource") { pekko ->
            val elements = Stream.from(listOf(1, 2))
                .mapOrFail { id -> if (id == 2) fail(Declined(id)) else Receipt(id) }
                .catchAll { Stream.empty() }
                .toSource()
                .runWith(Sink.seq(), pekko.system)
                .toCompletableFuture()
                .join()

            elements shouldBe listOf(Receipt(1))
        }

        test("orElse takes the other stream on a failure") { pekko ->
            val exit = Stream.from(listOf(1, 2))
                .mapOrFail { id -> if (id == 2) fail(Declined(id)) else Receipt(id) }
                .orElse(Stream.from(listOf(Receipt(9))))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(Receipt(1), Receipt(9)))
        }

        // Pekko's `orElse` is about an empty stream; this one is about a failed one.
        test("orElse leaves an empty stream empty") { pekko ->
            val exit = Stream.empty()
                .orElse(Stream.from(listOf(Receipt(9))))
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(emptyList())
        }

        test("prepend puts the stream it names in front of this one") { pekko ->
            val exit = Stream.of(2, 3).prepend(Stream.single(1)).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }

        test("concat puts the stream it names after this one") { pekko ->
            val exit = Stream.of(1, 2).concat(Stream.single(3)).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }

        test("a failure in what concat appends ends the run, after the elements before it") { pekko ->
            val seen = ConcurrentLinkedQueue<Int>()
            // A stream that fails where it runs rather than one already failed: `concat` materialises
            // both operands at once, so a `Stream.fail` would end the run before the first had emitted.
            val declining: Stream<Declined, Int> = Stream.of(3).mapOrFail { n -> if (n == 3) fail(Declined(n)) else n }

            val exit = Stream.of(1, 2)
                .concat(declining)
                .runWith(Sink.foreach { n: Int -> seen.add(n) })
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Failed(Declined(3))
            withClue("what the first operand emitted still reached the sink") {
                seen.toList() shouldBe listOf(1, 2)
            }
        }

        test("a failure in what prepend puts first ends the run") { pekko ->
            val exit = Stream.of(1, 2).prepend(Stream.fail(Declined(0))).runCollect().run(pekko.system).settled()

            exit shouldBe Exit.Failed(Declined(0))
        }

        test("a defect in what concat appends is nobody's declared failure, so it dies") { pekko ->
            val cause = IllegalStateException("no ledger")

            val exit = Stream.of(1, 2)
                .concat(Stream.single(3).map { n -> if (n == 3) throw cause else n })
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Died(cause)
        }

        // Demand is withheld until the burst is in, which is the condition conflate exists for: the
        // probe's explicit request is what makes "collapsed" a claim rather than a race.
        test("conflateWithSeed collapses a burst into one aggregate while downstream asks for nothing") { pekko ->
            val probe = Stream.from(listOf(1, 2, 3, 4))
                .conflateWithSeed({ n -> setOf(n) }, { seen, n -> seen + n })
                .toSource()
                .runWith(TestSink.probe(pekko.classic), pekko.system)

            probe.ensureSubscription()
            probe.expectNoMessage(Duration.ofMillis(200))

            probe.requestNext() shouldBe setOf(1, 2, 3, 4)
            probe.expectComplete()
        }

        // The aggregate runs only where a burst forms, so the withheld demand is what reaches it.
        test("an aggregate that throws dies naming the operator and the element it was folding in") { pekko ->
            val cause = IllegalStateException("no room")

            val probe = Stream.from(listOf(1, 2, 3))
                .conflateWithSeed({ n -> setOf(n) }, { _, _ -> throw cause })
                .toSource()
                .runWith(TestSink.probe(pekko.classic), pekko.system)

            val thrown = probe.expectSubscriptionAndError()

            thrown shouldBe cause
            thrown.suppressed.single().message shouldContain "conflateWithSeed died on 2"
        }

        test("mapConcat flattens what each element answers with, in the order it answered") { pekko ->
            val exit = Stream.of(listOf(1, 2), emptyList(), listOf(3))
                .mapConcat { ns -> ns }
                .runCollect()
                .run(pekko.system)
                .settled()

            exit shouldBe Exit.Done(listOf(1, 2, 3))
        }
    }
}
