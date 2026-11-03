package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.consume
import dev.surefold.sqs.divertLefts
import dev.surefold.sqs.runDeleting
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import dev.surefold.stream.take
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds

/** SNS as its API answers a batch: a body in [refused] is refused, and "down" means the service is unreachable. */
private class FakeBatchSns(private val refused: Set<String> = emptySet()) : SnsClient {
    val batches = ConcurrentLinkedQueue<PublishBatchRequest>()

    override fun publishBatch(request: PublishBatchRequest): PublishBatchResponse {
        if (request.publishBatchRequestEntries().any { it.message() == "down" }) {
            throw AwsServiceException.builder().message("unreachable").build()
        }
        batches += request
        val (bad, good) = request.publishBatchRequestEntries().partition { it.message() in refused }
        val accepted = good.map { entry ->
            PublishBatchResultEntry.builder().id(entry.id()).messageId("id-${entry.message()}")
        }
        val rejected = bad.map { BatchResultErrorEntry.builder().id(it.id()).code("InvalidParameter") }
        return PublishBatchResponse.builder()
            .successful(accepted.map { it.build() })
            .failed(rejected.map { it.senderFault(true).build() })
            .build()
    }

    override fun serviceName() = "sns"

    override fun close() = Unit
}

/** A queue that hands each message out once, and keeps the receipts it was asked to delete. */
private class FakeQueue(vararg bodies: String) : SqsClient {
    private val waiting =
        ConcurrentLinkedQueue(bodies.map { Message.builder().body(it).receiptHandle("r-$it").build() })
    val deleted = ConcurrentLinkedQueue<String>()

    override fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse {
        val taken = generateSequence { waiting.poll() }.take(request.maxNumberOfMessages()).toList()
        if (taken.isEmpty()) Thread.sleep(QUIET_MILLIS)
        return ReceiveMessageResponse.builder().messages(taken).build()
    }

    override fun deleteMessageBatch(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse {
        deleted += request.entries().map { it.receiptHandle() }
        return DeleteMessageBatchResponse.builder().build()
    }

    override fun serviceName() = "sqs"

    override fun close() = Unit

    companion object {
        const val QUIET_MILLIS = 20L
    }
}

object BatchSuite : Suite() {
    private val topic = SnsTopic("arn:aws:sns:eu-west-1:123456789012:orders")

    init {
        test("a batch answers per message, and one refused fails only itself") {
            val sns = FakeBatchSns(refused = setOf("b"))

            val outcomes = SnsPublisher(sns).publishAll(listOf("a", "b", "c").map { topic.message(it) })

            sns.batches.single().publishBatchRequestEntries().size shouldBe 3
            outcomes[0] shouldBeRight Published("id-a", null)
            outcomes[1].shouldBeLeft { failed ->
                failed.cause.shouldBeInstanceOf<SnsRefused> { it.code shouldBe "InvalidParameter" }
            }
            outcomes[2] shouldBeRight Published("id-c", null)
        }

        test("a batch the service fails is a Left for every message in it") {
            val outcomes = SnsPublisher(FakeBatchSns()).publishAll(listOf(topic.message("a"), topic.message("down")))
            outcomes.count { it is Either.Left<*> } shouldBe 2
        }

        test("a batch of more than SNS's ten is refused before anything is published") {
            Either.catch { SnsPublisher(FakeBatchSns()).publishAll((1..11).map { topic.message("$it") }) }
                .shouldBeLeft { it.shouldBeInstanceOf<IllegalArgumentException>() }
        }

        test("publishBatch publishes up to ten at once and passes each element on as its own outcome") {
            val sns = FakeBatchSns(refused = setOf("3"))

            val exit = Stream.from((1..12).toList())
                .publishBatch(SnsPublisher(sns), within = 1.hours) { topic.message("$it") }
                .runCollect().run(Forks()).toCompletableFuture().join()

            sns.batches.map { it.publishBatchRequestEntries().size } shouldBe listOf(10, 2)
            exit.shouldBeInstanceOf<Exit.Done<*>> { done ->
                (done.value as List<*>).map { it is Either.Right<*> } shouldBe (1..12).map { it != 3 }
            }
        }

        test("publishMessageBatch over messages read from SQS deletes each only once its own publish succeeded") {
            val sqs = FakeQueue("1", "2", "3")
            val failed = ConcurrentLinkedQueue<String>()

            val exit = Sqs.consume(sqs, Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders"))
                .publishMessageBatch(SnsPublisher(FakeBatchSns(refused = setOf("2"))), within = 100.milliseconds) { m ->
                    topic.message(m.body())
                }
                .divertLefts { publish -> failed += publish.message.body }
                .take(2)
                .runDeleting()
                .run(Forks())
                .toCompletableFuture().get(5, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(2L)
            failed.toList() shouldBe listOf("2")
            sqs.deleted.sorted() shouldBe listOf("r-1", "r-3")
        }
    }
}
