package dev.surefold.sqs

import dev.surefold.capturingMetrics
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.withClue
import dev.surefold.metricTagged
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** A queue that hands each message out once, and refuses to delete those whose bodies are in [stale]. */
private class MeteredQueue(vararg bodies: String, private val stale: Set<String> = emptySet()) : SqsClient {
    private val waiting = ConcurrentLinkedQueue(bodies.map { Message.builder().body(it).receiptHandle(it).build() })

    override fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse {
        val taken = generateSequence { waiting.poll() }.take(request.maxNumberOfMessages()).toList()
        if (taken.isEmpty()) Thread.sleep(QUIET_MILLIS)
        return ReceiveMessageResponse.builder().messages(taken).build()
    }

    override fun deleteMessageBatch(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse {
        val (refused, deleted) = request.entries().partition { it.receiptHandle() in stale }
        return DeleteMessageBatchResponse.builder()
            .successful(deleted.map { DeleteMessageBatchResultEntry.builder().id(it.id()).build() })
            .failed(refused.map { BatchResultErrorEntry.builder().id(it.id()).code(STALE).senderFault(true).build() })
            .build()
    }

    override fun serviceName() = "sqs"

    override fun close() = Unit

    companion object {
        const val QUIET_MILLIS = 20L
        const val STALE = "ReceiptHandleIsInvalid"
    }
}

object MetersSuite : Suite() {
    private val queue = Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders")

    init {
        test("a run records what it received, how long it waited, and what it deleted, tagged with its queue") {
            val inFlight = ConcurrentLinkedQueue<Double>()
            val (exit, captured) = capturingMetrics { captured ->
                metricTagged("service" to "shop") {
                    Sqs.consume(MeteredQueue("one", "two", "three"), queue, extendVisibility = false)
                        .mapMessage { message ->
                            captured.gauge("surefold.sqs.consumer.in.flight")?.let(inFlight::add)
                            message.body()
                        }
                        .take(3)
                        .runDeleting()
                        .run(Forks())
                        .toCompletableFuture().get(5, TimeUnit.SECONDS)
                } to captured
            }

            exit shouldBe Exit.Done(3L)
            captured.counter("surefold.sqs.consumer.received") shouldBe 3.0
            withClue("each body's bytes") { captured.counter("surefold.sqs.consumer.bytes") shouldBe 11.0 }
            (captured.histogram("surefold.sqs.consumer.receive").isNotEmpty()) shouldBe true
            captured.counter("surefold.sqs.consumer.deleted") shouldBe 3.0
            withClue("each message was in flight while it was handled: $inFlight") {
                inFlight.size shouldBe 3
                inFlight.all { it >= 1.0 } shouldBe true
            }
            withClue("nothing is in flight once the run has ended") {
                captured.gauge("surefold.sqs.consumer.in.flight") shouldBe 0.0
            }
            captured.tags("surefold.sqs.consumer.received").entries shouldContainAll
                mapOf("service" to "shop", "queue" to "orders").entries
        }

        test("a delete SQS refuses is counted, tagged with its code") {
            val (exit, captured) = capturingMetrics { captured ->
                Sqs.consume(MeteredQueue("one", "two", stale = setOf("two")), queue, extendVisibility = false)
                    .take(2)
                    .runDeleting()
                    .run(Forks())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS) to captured
            }

            exit shouldBe Exit.Done(2L)
            captured.counter("surefold.sqs.consumer.deleted") shouldBe 1.0
            captured.counter("surefold.sqs.consumer.delete.failures") shouldBe 1.0
            captured.tags("surefold.sqs.consumer.delete.failures")["code"] shouldBe MeteredQueue.STALE
        }
    }
}
