package dev.surefold.sqs.guide

import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.sqs.Depth
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.SqsQueues
import dev.surefold.sqs.consume
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.sqsQueues
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import kotlin.time.Duration.Companion.seconds

object OrdersSuite : SharedSuite<SqsQueues>() {
    override val shared = sqsQueues()

    init {
        test("every order read is deleted") { sqs ->
            val orders = sqs.create("guide-orders", deadLetters = sqs.create("guide-orders-dlq"))
            sqs.send(orders, "o-1", "o-2")

            Sqs.consume(sqs.client, orders, waitTime = 1.seconds).take(2).runDeleting().run(Forks())
                .toCompletableFuture().join() shouldBe Exit.Done(2L)

            sqs.depth(orders) shouldBe Depth(visible = 0, inFlight = 0)
        }
    }
}
