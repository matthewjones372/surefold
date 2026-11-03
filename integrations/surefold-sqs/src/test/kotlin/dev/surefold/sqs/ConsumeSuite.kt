package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.TestStreams
import dev.surefold.stream.run
import dev.surefold.stream.start
import dev.surefold.stream.take
import software.amazon.awssdk.core.exception.AbortedException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequest
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResponse
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchResultEntry
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** A queue that hands each message out once, as SQS does within a visibility timeout, and keeps what was deleted. */
private class FakeQueue(vararg bodies: String, failFirstDelete: Boolean = false) : SqsClient {
    private val failed = AtomicBoolean(!failFirstDelete)

    private val waiting =
        ConcurrentLinkedQueue(bodies.map { Message.builder().body(it).receiptHandle("r-$it").build() })
    val deleted = ConcurrentLinkedQueue<String>()
    val asked = ConcurrentLinkedQueue<ReceiveMessageRequest>()

    override fun receiveMessage(request: ReceiveMessageRequest): ReceiveMessageResponse {
        asked += request
        val taken = generateSequence { waiting.poll() }.take(request.maxNumberOfMessages()).toList()
        // An empty queue answers after a short wait rather than SQS's twenty seconds, which a stop interrupts.
        if (taken.isEmpty()) Thread.sleep(QUIET_MILLIS)
        return ReceiveMessageResponse.builder().messages(taken).build()
    }

    override fun deleteMessageBatch(request: DeleteMessageBatchRequest): DeleteMessageBatchResponse {
        // As a call a stop's interrupt aborts: thrown, with nothing deleted.
        if (failed.compareAndSet(false, true)) throw AbortedException.builder().message("aborted").build()
        deleted += request.entries().map { it.receiptHandle() }
        return DeleteMessageBatchResponse.builder()
            .successful(request.entries().map { DeleteMessageBatchResultEntry.builder().id(it.id()).build() })
            .build()
    }

    override fun serviceName() = "sqs"

    override fun close() = Unit

    companion object {
        const val QUIET_MILLIS = 20L
    }
}

private data object Refused

object ConsumeSuite : Suite() {
    private val queue = Queue("https://sqs.eu-west-1.amazonaws.com/123456789012/orders")

    private val backends: List<Pair<String, () -> StreamBackend>> =
        listOf("Forks" to { Forks() }, "TestStreams" to { TestStreams() })

    init {
        backends.forEach { (name, backend) ->
            test("the messages a run handles are deleted by the time it ends [$name]") {
                val sqs = FakeQueue("1", "2", "3", "4", "5")

                Sqs.consume(sqs, queue).take(5).runDeleting().run(backend())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS) shouldBe Exit.Done(5L)

                sqs.deleted.sorted() shouldBe listOf("r-1", "r-2", "r-3", "r-4", "r-5")
            }

            test("a message whose element failed is left on the queue [$name]") {
                val sqs = FakeQueue("1", "2", "3", "4")

                val exit = Sqs.consume(sqs, queue)
                    .mapMessage { it.body() }
                    .mapMessageOrFail { body -> if (body == "3") fail(Refused) else body }
                    .runDeleting()
                    .run(backend())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)

                exit shouldBe Exit.Failed(Refused)
                withClue("only what reached the end before the failure is deleted") {
                    sqs.deleted.sorted() shouldBe listOf("r-1", "r-2")
                }
            }

            test("a diverted Left is handed over and its message left for the queue's redrive [$name]") {
                val sqs = FakeQueue("1", "bad", "3")
                val diverted = ConcurrentLinkedQueue<String>()

                val exit = Sqs.consume(sqs, queue)
                    .mapMessage { m -> if (m.body() == "bad") Either.Left(m.body()) else Either.Right(m.body()) }
                    .divertLefts { diverted += it }
                    .take(2)
                    .runDeleting()
                    .run(backend())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)

                exit shouldBe Exit.Done(2L)
                diverted.toList() shouldBe listOf("bad")
                sqs.deleted.sorted() shouldBe listOf("r-1", "r-3")
            }

            test("absolve ends the run at the first Left, with what came before it deleted [$name]") {
                val sqs = FakeQueue("1", "bad", "3")

                val exit = Sqs.consume(sqs, queue)
                    .mapMessage<Nothing, Message, Either<Refused, String>> { m ->
                        if (m.body() == "bad") Either.Left(Refused) else Either.Right(m.body())
                    }
                    .absolve()
                    .runDeleting()
                    .run(backend())
                    .toCompletableFuture().get(5, TimeUnit.SECONDS)

                exit shouldBe Exit.Failed(Refused)
                sqs.deleted.toList() shouldBe listOf("r-1")
            }
        }

        test("mapParMessage keeps the queue's order and deletes each message once") {
            val sqs = FakeQueue(*(1..30).map { "$it" }.toTypedArray())
            val seen = ConcurrentLinkedQueue<String>()

            val exit = Sqs.consume(sqs, queue)
                .mapParMessage(8) { m -> m.body() }
                .mapMessage { seen += it }
                .take(30)
                .runDeleting()
                .run(Forks())
                .toCompletableFuture().get(5, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(30L)
            seen.toList() shouldBe (1..30).map { "$it" }
            sqs.deleted.size shouldBe 30
        }

        test("a delete that throws keeps its messages, and the close deletes them") {
            val sqs = FakeQueue("1", "2", failFirstDelete = true)

            val exit = Sqs.consume(sqs, queue, maxMessages = 1).runDeleting().run(Forks())
                .toCompletableFuture().get(5, TimeUnit.SECONDS)

            exit.shouldBeInstanceOf<Exit.Died>()
            withClue("the first message was handled before the delete that threw, so the close deletes it") {
                ("r-1" in sqs.deleted) shouldBe true
            }
        }

        test("a receive asks for SQS's long poll, ten at a time, with every attribute") {
            val sqs = FakeQueue("1")

            Sqs.consume(sqs, queue).take(1).runDeleting().run(Forks()).toCompletableFuture().get(5, TimeUnit.SECONDS)

            val asked = sqs.asked.first()
            asked.queueUrl() shouldBe queue.url
            asked.waitTimeSeconds() shouldBe 20
            asked.maxNumberOfMessages() shouldBe 10
            asked.messageAttributeNames() shouldBe listOf("All")
        }

        test("a run on a quiet queue ends when stopped") {
            val running = Sqs.consume(FakeQueue(), queue).runDeleting().start(Forks())

            running.stop()

            running.exit.toCompletableFuture().get(5, TimeUnit.SECONDS).shouldBeInstanceOf<Exit.Done<*>>()
        }
    }
}
