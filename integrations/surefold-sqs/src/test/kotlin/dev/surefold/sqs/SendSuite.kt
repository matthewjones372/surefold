package dev.surefold.sqs

import dev.surefold.capturingMetrics
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.SendMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import software.amazon.awssdk.services.sqs.model.SendMessageResponse
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.hours

/** SQS as its API answers a send: a body named in [refused] is refused, and "down" means the service is unreachable. */
private class FakeSqs(private val refused: Set<String> = emptySet()) : SqsClient {
    val sent = ConcurrentLinkedQueue<SendMessageRequest>()
    val batches = ConcurrentLinkedQueue<SendMessageBatchRequest>()

    override fun sendMessage(request: SendMessageRequest): SendMessageResponse {
        if (request.messageBody() == "down") throw AwsServiceException.builder().message("unreachable").build()
        sent += request
        return SendMessageResponse.builder().messageId("id-${request.messageBody()}").build()
    }

    override fun sendMessageBatch(request: SendMessageBatchRequest): SendMessageBatchResponse {
        if (request.entries().any { it.messageBody() == "down" }) {
            throw AwsServiceException.builder().message("unreachable").build()
        }
        batches += request
        val (bad, good) = request.entries().partition { it.messageBody() in refused }
        val accepted = good.map { entry ->
            SendMessageBatchResultEntry.builder().id(entry.id()).messageId("id-${entry.messageBody()}")
        }
        val rejected = bad.map { BatchResultErrorEntry.builder().id(it.id()).code("InvalidMessageContents") }
        return SendMessageBatchResponse.builder()
            .successful(accepted.map { it.build() })
            .failed(rejected.map { it.senderFault(true).build() })
            .build()
    }

    override fun serviceName() = "sqs"

    override fun close() = Unit
}

object SendSuite : Suite() {
    private val queue = Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders")
    private val fifo = Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders.fifo")

    init {
        test("a message sent goes to its queue with its attributes and answers SQS's id") {
            val sqs = FakeSqs()

            sqs.send(queue.message("a", attributes = mapOf("kind" to "order"))) shouldBeRight Sent("id-a", null)

            val request = sqs.sent.single()
            request.queueUrl() shouldBe queue.url
            request.messageAttributes()["kind"]?.stringValue() shouldBe "order"
        }

        test("a FIFO message carries its group and deduplication ids") {
            val sqs = FakeSqs()

            sqs.send(fifo.message("a", groupId = "customer-1", dedupId = "order-1"))

            val request = sqs.sent.single()
            request.messageGroupId() shouldBe "customer-1"
            request.messageDeduplicationId() shouldBe "order-1"
        }

        test("a group id on a standard queue is refused before anything is sent") {
            val thrown = Either.catch { queue.message("a", groupId = "customer-1") }
            thrown.shouldBeLeft { it.shouldBeInstanceOf<IllegalArgumentException>() }
        }

        test("a send the service fails is a Left naming the message") {
            val outcome = FakeSqs().send(queue.message("down"))
            outcome.shouldBeLeft { failed -> failed.message.body shouldBe "down" }
        }

        test("a batch answers per message, and one refused fails only itself") {
            val sqs = FakeSqs(refused = setOf("b"))

            val outcomes = sqs.sendAll(listOf(queue.message("a"), queue.message("b"), queue.message("c")))

            sqs.batches.single().entries().size shouldBe 3
            outcomes[0] shouldBeRight Sent("id-a", null)
            outcomes[1].shouldBeLeft { failed ->
                failed.cause.shouldBeInstanceOf<SqsRefused> { it.code shouldBe "InvalidMessageContents" }
            }
            outcomes[2] shouldBeRight Sent("id-c", null)
        }

        test("a batch the service fails is a Left for every message in it") {
            val outcomes = FakeSqs().sendAll(listOf(queue.message("a"), queue.message("down")))
            withClue("both messages were in the one request that failed") {
                outcomes.count { it is Either.Left<*> } shouldBe 2
            }
        }

        test("a batch of more than SQS's ten is refused before anything is sent") {
            val thrown = Either.catch { FakeSqs().sendAll((1..11).map { queue.message("$it") }) }
            thrown.shouldBeLeft { it.shouldBeInstanceOf<IllegalArgumentException>() }
        }

        test("a send records what was sent and how long it took, tagged with its queue") {
            val captured = capturingMetrics { captured ->
                FakeSqs().send(queue.message("a"))
                captured
            }

            captured.counter("surefold.sqs.producer.sent") shouldBe 1.0
            captured.histogram("surefold.sqs.producer.send").size shouldBe 1
            captured.tags("surefold.sqs.producer.sent")["queue"] shouldBe "orders"
        }

        test("a batch with one refused entry records one failure, tagged with its code") {
            val captured = capturingMetrics { captured ->
                FakeSqs(refused = setOf("b")).sendAll(listOf("a", "b", "c").map { queue.message(it) })
                captured
            }

            captured.counter("surefold.sqs.producer.sent") shouldBe 2.0
            captured.counter("surefold.sqs.producer.failures") shouldBe 1.0
            captured.tags("surefold.sqs.producer.failures")["code"] shouldBe "InvalidMessageContents"
        }

        test("sendTo records to the meters in scope where it is described") {
            val captured = capturingMetrics { captured ->
                Stream.from(listOf(1, 2, 3)).sendTo(FakeSqs()) { queue.message("$it") }
                    .runCollect().run(Forks()).toCompletableFuture().join()
                captured
            }

            captured.counter("surefold.sqs.producer.sent") shouldBe 3.0
        }

        test("sendTo passes each element on, in order, once SQS has it") {
            val sqs = FakeSqs()

            val exit = Stream.from((1..20).toList()).sendTo(sqs, inFlight = 4) { queue.message("$it") }
                .runCollect().run(Forks()).toCompletableFuture().join()

            exit shouldBe Exit.Done((1..20).toList())
            sqs.sent.map { it.messageBody() }.sorted() shouldBe (1..20).map { "$it" }.sorted()
        }

        test("sendTo ends the run with a defect when a send fails") {
            val exit = Stream.from(listOf("a", "down", "c")).sendTo(FakeSqs()) { queue.message(it) }
                .runCollect().run(Forks()).toCompletableFuture().join()

            exit.shouldBeInstanceOf<Exit.Died>()
        }

        test("sendBatch sends up to ten at once and passes each element on as its own outcome") {
            val sqs = FakeSqs(refused = setOf("3"))

            val exit = Stream.from((1..12).toList()).sendBatch(sqs, within = 1.hours) { queue.message("$it") }
                .runCollect().run(Forks()).toCompletableFuture().join()

            sqs.batches.map { it.entries().size } shouldBe listOf(10, 2)
            exit.shouldBeInstanceOf<Exit.Done<*>> { done ->
                (done.value as List<*>).map { it is Either.Right<*> } shouldBe (1..12).map { it != 3 }
            }
        }
    }
}
