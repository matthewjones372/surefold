package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import dev.surefold.test.eventually
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private data object NotPlaced

/** The claims ConsumeSuite and VisibilitySuite make against fakes, held against SQS's own protocol on ElasticMQ. */
object ElasticMqSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()

    private const val SLOW_MILLIS = 2500L

    init {
        test("only what reached the end is deleted, and the rest stays on the queue") { sqs ->
            val orders = sqs.create("consumed")
            sqs.send(orders, "1", "2", "3", "4")

            val exit = Sqs.consume(sqs.client, orders, waitTime = 1.seconds)
                .mapMessage { it.body() }
                .mapMessageOrFail { body -> if (body == "3") fail(NotPlaced) else body }
                .runDeleting()
                .run(Forks())
                .toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit shouldBe Exit.Failed(NotPlaced)
            sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 2)
        }

        listOf(true, false).forEach { extend ->
            test("a handler slower than the timeout sees its message ${if (extend) "once" else "again"}") { sqs ->
                val orders = sqs.create("slow-$extend", visibility = 1.seconds)
                sqs.send(orders, "slow")
                val seen = ConcurrentLinkedQueue<String>()

                Sqs.consume(sqs.client, orders, waitTime = 1.seconds, visibility = 1.seconds, extendVisibility = extend)
                    .mapParMessage(4) { m ->
                        seen += m.body()
                        // Only the first copy is slow; one received again while it sleeps passes straight on.
                        if (seen.size == 1) Thread.sleep(SLOW_MILLIS)
                    }
                    .take(1)
                    .runDeleting()
                    .run(Forks())
                    .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(1L)

                (seen.size > 1) shouldBe !extend
            }
        }

        test("a diverted message comes back and, past maxReceives, moves to its dead letters") { sqs ->
            val dead = sqs.create("diverted-dead")
            val orders = sqs.create("diverted", visibility = 1.seconds, deadLetters = dead, maxReceives = 1)
            sqs.send(orders, "bad", "ok")

            Sqs.consume(sqs.client, orders, waitTime = 1.seconds, visibility = 1.seconds)
                .mapMessage { m -> if (m.body() == "bad") Either.Left(m.body()) else Either.Right(m.body()) }
                .divertLefts { }
                .take(1)
                .runDeleting()
                .run(Forks())
                .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(1L)

            // Spaced: receives a few milliseconds apart as the timeout lapses can see ElasticMQ move a message twice.
            eventually(within = 10.seconds, every = 300.milliseconds) {
                sqs.receive(orders)
                (sqs.depth(dead).visible > 0) shouldBe true
            }
            sqs.receive(dead).map { it.body() }.distinct() shouldBe listOf("bad")
        }
    }
}
