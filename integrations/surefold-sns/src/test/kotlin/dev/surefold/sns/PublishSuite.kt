package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import software.amazon.awssdk.awscore.exception.AwsServiceException
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import java.util.concurrent.ConcurrentLinkedQueue

/** SNS as its API answers a publish; a message whose body is "down" means the service is unreachable. */
internal class FakeSns : SnsClient {
    val published = ConcurrentLinkedQueue<PublishRequest>()

    override fun publish(request: PublishRequest): PublishResponse {
        if (request.message() == "down") throw AwsServiceException.builder().message("unreachable").build()
        published += request
        return PublishResponse.builder().messageId("id-${request.message()}").build()
    }

    override fun serviceName() = "sns"

    override fun close() = Unit
}

object PublishSuite : Suite() {
    private val topic = SnsTopic("arn:aws:sns:eu-west-1:123456789012:orders")
    private val fifo = SnsTopic("arn:aws:sns:eu-west-1:123456789012:orders.fifo")

    init {
        test("a message published goes to its topic with its attributes and answers SNS's id") {
            val sns = FakeSns()

            SnsPublisher(sns).publish(topic.message("a", mapOf("kind" to "order"))) shouldBeRight
                Published("id-a", null)

            val request = sns.published.single()
            request.topicArn() shouldBe topic.arn
            request.messageAttributes()["kind"]?.stringValue() shouldBe "order"
        }

        test("a FIFO message carries its group and deduplication ids") {
            val sns = FakeSns()

            SnsPublisher(sns).publish(fifo.message("a", groupId = "customer-1", dedupId = "order-1"))

            val request = sns.published.single()
            request.messageGroupId() shouldBe "customer-1"
            request.messageDeduplicationId() shouldBe "order-1"
        }

        test("a group id on a standard topic is refused before anything is published") {
            Either.catch { topic.message("a", groupId = "customer-1") }
                .shouldBeLeft { it.shouldBeInstanceOf<IllegalArgumentException>() }
        }

        test("a publish the service fails is a Left naming the message") {
            SnsPublisher(FakeSns()).publish(topic.message("down"))
                .shouldBeLeft { failed -> failed.message.body shouldBe "down" }
        }

        test("publishTo passes each element on, in order, once SNS has it") {
            val sns = FakeSns()

            val exit = Stream.from((1..20).toList()).publishTo(SnsPublisher(sns), inFlight = 4) { topic.message("$it") }
                .runCollect().run(Forks()).toCompletableFuture().join()

            exit shouldBe Exit.Done((1..20).toList())
            sns.published.map { it.message() }.sorted() shouldBe (1..20).map { "$it" }.sorted()
        }

        test("publishTo ends the run with a defect when a publish fails") {
            val exit = Stream.from(listOf("a", "down", "c")).publishTo(SnsPublisher(FakeSns())) { topic.message(it) }
                .runCollect().run(Forks()).toCompletableFuture().join()

            exit.shouldBeInstanceOf<Exit.Died>()
        }
    }
}
