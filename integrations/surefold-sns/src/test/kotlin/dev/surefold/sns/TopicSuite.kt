package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.sns.guide.announcing
import dev.surefold.sqs.Depth
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import dev.surefold.stream.start
import dev.surefold.test.eventually
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** surefold-sns's claims held on a topic that delivers to real queues, rather than on a client that only keeps. */
object TopicSuite : SharedSuite<SnsTopics>() {
    override val shared = snsTopics()

    init {
        test("the guide's announcing reads one queue, publishes to a topic, and the topic delivers to another") { sns ->
            val orders = sns.queues.create("orders")
            val placed = sns.create("placed")
            val billing = sns.queues.create("billing")
            sns.subscribe(placed, billing)
            sns.queues.send(orders, "o-1", "o-2", "o-3")

            val running = announcing(sns.queues.client, sns.client, orders, placed).start(Forks())
            eventually(within = 10.seconds) {
                sns.queues.receive(billing).map { it.body() }.sorted() shouldBe listOf("o-1", "o-2", "o-3")
            }
            running.stop()
            running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS)

            eventually(within = 5.seconds) {
                sns.queues.depth(orders) shouldBe Depth(visible = 0, inFlight = 0)
            }
        }

        test("publishBatch passes on an entry the topic refused as its own Left, and delivers the rest") { sns ->
            val placed = sns.create("placed-batch")
            val billing = sns.queues.create("billing-batch")
            sns.subscribe(placed, billing)

            val exit = Stream.from(listOf("a", "", "c"))
                .publishBatch(SnsPublisher(sns.client), within = 1.hours) { placed.message(it) }
                .runCollect().run(Forks()).toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<dev.surefold.stream.Exit.Done<*>> { done ->
                (done.value as List<*>).map { it is Either.Right<*> } shouldBe listOf(true, false, true)
            }
            sns.queues.receive(billing).map { it.body() }.sorted() shouldBe listOf("a", "c")
        }

        test("a group id on a FIFO topic reaches its FIFO queue in order") { sns ->
            val placed = sns.create("placed.fifo", fifo = true)
            val billing = sns.queues.create("billing.fifo", fifo = true)
            sns.subscribe(placed, billing)

            Stream.from(listOf("1", "2", "3"))
                .publishTo(SnsPublisher(sns.client), inFlight = 1) { placed.message(it, groupId = "c-1", dedupId = it) }
                .runCollect().run(Forks()).toCompletableFuture().get(10, TimeUnit.SECONDS)

            sns.queues.receive(billing).map { it.body() } shouldBe listOf("1", "2", "3")
        }

        test("a publish the topic refuses is a Left naming the message") { sns ->
            SnsPublisher(sns.client).publish(sns.create("placed-empty").message(""))
                .shouldBeLeft { failed -> failed.message.body shouldBe "" }
        }
    }
}
