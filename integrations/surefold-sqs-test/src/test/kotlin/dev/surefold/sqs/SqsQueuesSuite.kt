package dev.surefold.sqs

import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

object SqsQueuesSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()

    init {
        test("a message sent is received, and stays on the queue until it is deleted") { sqs ->
            val orders = sqs.create("orders-received")
            sqs.send(orders, "o-1", "o-2")

            sqs.receive(orders).map { it.body() } shouldBe listOf("o-1", "o-2")
            sqs.depth(orders) shouldBe Depth(visible = 2, inFlight = 0)
        }

        test("a run of surefold-sqs deletes what it handled") { sqs ->
            val orders = sqs.create("orders-deleted")
            sqs.send(orders, "o-1", "o-2", "o-3")

            Sqs.consume(sqs.client, orders, waitTime = 1.seconds).take(3).runDeleting().run(Forks())
                .toCompletableFuture().get(10, TimeUnit.SECONDS) shouldBe Exit.Done(3L)

            sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 0)
        }

        test("a message received more than maxReceives times moves to its dead letters") { sqs ->
            val dead = sqs.create("orders-dead")
            val orders = sqs.create("orders-redriven", deadLetters = dead, maxReceives = 1)
            sqs.send(orders, "o-1")

            sqs.receive(orders)
            withClue("the second receive is past maxReceives, so SQS moves it rather than handing it out") {
                sqs.receive(orders) shouldBe emptyList()
            }
            sqs.depth(dead) shouldBe Depth(visible = 1, inFlight = 0)
        }

        test("a FIFO queue hands a group's messages out in order") { sqs ->
            val orders = sqs.create("orders.fifo", fifo = true)
            sqs.send(orders, "o-1", "o-2", "o-3")

            sqs.receive(orders).map { it.body() } shouldBe listOf("o-1", "o-2", "o-3")
        }
    }
}
