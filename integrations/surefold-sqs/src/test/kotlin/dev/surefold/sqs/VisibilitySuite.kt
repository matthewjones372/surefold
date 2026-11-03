package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequest
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchResponse
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/**
 * A queue that hides a received message for the receive's visibility timeout, as SQS does, and counts receives. In
 * the order the messages were put on it, so each call holds the lock.
 */
private class TimedQueue(vararg bodies: String) : SqsClient {
    private val visibleAt = bodies.associateWithTo(LinkedHashMap()) { 0L }
    val received = ConcurrentLinkedQueue<String>()
    val madeVisible = ConcurrentLinkedQueue<String>()

    override fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse {
        val due = synchronized(this) {
            val now = System.currentTimeMillis()
            visibleAt.filterValues { it <= now }.keys.take(request.maxNumberOfMessages()).onEach {
                visibleAt[it] = now + request.visibilityTimeout() * MILLIS
            }
        }
        received += due
        // An empty queue answers after a short wait rather than SQS's twenty seconds.
        if (due.isEmpty()) Thread.sleep(QUIET_MILLIS)
        return ReceiveMessageResponse.builder().messages(due.map { message(it) }).build()
    }

    @Synchronized
    override fun changeMessageVisibilityBatch(
        request: ChangeMessageVisibilityBatchRequest,
    ): ChangeMessageVisibilityBatchResponse {
        val now = System.currentTimeMillis()
        request.entries().forEach { entry ->
            visibleAt.computeIfPresent(entry.receiptHandle().removePrefix("r-")) { _, _ ->
                now + entry.visibilityTimeout() * MILLIS
            }
        }
        return ChangeMessageVisibilityBatchResponse.builder().build()
    }

    @Synchronized
    override fun changeMessageVisibility(request: ChangeMessageVisibilityRequest): ChangeMessageVisibilityResponse {
        val body = request.receiptHandle().removePrefix("r-")
        if (request.visibilityTimeout() == 0) madeVisible += body
        visibleAt.computeIfPresent(body) { _, _ -> System.currentTimeMillis() + request.visibilityTimeout() * MILLIS }
        return ChangeMessageVisibilityResponse.builder().build()
    }

    @Synchronized
    override fun deleteMessageBatch(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse {
        request.entries().forEach { visibleAt.remove(it.receiptHandle().removePrefix("r-")) }
        return DeleteMessageBatchResponse.builder().build()
    }

    override fun serviceName() = "sqs"

    override fun close() = Unit

    private fun message(body: String) = Message.builder().body(body).receiptHandle("r-$body").build()

    companion object {
        const val MILLIS = 1000L
        const val QUIET_MILLIS = 20L
    }
}

object VisibilitySuite : Suite() {
    private val queue = Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders")

    private const val SLOW_MILLIS = 2500L

    /** One slow message, handled while the reader keeps polling, on a one-second visibility timeout. */
    private fun slowly(sqs: TimedQueue, extend: Boolean) =
        Sqs.consume(sqs, queue, visibility = 1.seconds, extendVisibility = extend)
            .mapParMessage(4) { m -> if (m.body() == "slow") Thread.sleep(SLOW_MILLIS) }
            .take(1)
            .runDeleting()
            .run(Forks())
            .toCompletableFuture().get(10, TimeUnit.SECONDS)

    init {
        test("a handler slower than the visibility timeout sees its message once") {
            val sqs = TimedQueue("slow")

            slowly(sqs, extend = true) shouldBe Exit.Done(1L)

            sqs.received.toList() shouldBe listOf("slow")
        }

        test("without extension, a slow handler's message comes back while it is still being handled") {
            val sqs = TimedQueue("slow")

            slowly(sqs, extend = false) shouldBe Exit.Done(1L)

            withClue("received again once its one-second timeout lapsed") {
                sqs.received.count { it == "slow" } shouldBeGreaterThan 1
            }
        }

        test("a diverted Left with nack is made visible at once, and the rest are not") {
            val sqs = TimedQueue("1", "bad", "3")
            val diverted = ConcurrentLinkedQueue<String>()

            val exit = Sqs.consume(sqs, queue)
                .mapMessage { m -> if (m.body() == "bad") Either.Left(m.body()) else Either.Right(m.body()) }
                .divertLefts(nack = true) { diverted += it }
                .take(2)
                .runDeleting()
                .run(Forks())
                .toCompletableFuture().get(10, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(2L)
            withClue("a nacked message is visible at once, so the reader may receive it again before the run ends") {
                diverted.distinct() shouldBe listOf("bad")
                sqs.madeVisible.distinct() shouldBe listOf("bad")
            }
        }
    }
}
