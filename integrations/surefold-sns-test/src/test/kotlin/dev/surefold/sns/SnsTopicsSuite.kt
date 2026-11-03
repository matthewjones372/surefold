package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLeft
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.withClue
import software.amazon.awssdk.services.sns.model.InvalidParameterException
import software.amazon.awssdk.services.sns.model.TooManyEntriesInBatchRequestException

object SnsTopicsSuite : SharedSuite<SnsTopics>() {
    override val shared = snsTopics()

    init {
        test("a message published reaches every queue subscribed, raw or in SNS's envelope") { sns ->
            val placed = sns.create("placed")
            val billing = sns.queues.create("billing")
            val audit = sns.queues.create("audit")
            sns.subscribe(placed, billing)
            sns.subscribe(placed, audit, raw = false)

            SnsPublisher(sns.client).publish(placed.message("o-1", mapOf("kind" to "order"))).shouldBeRight { }

            // SqsQueues.receive asks for no attributes, so this one asks for them itself.
            val raw = sns.queues.client.receiveMessage { it.queueUrl(billing.url).messageAttributeNames("All") }
                .messages().single()
            raw.body() shouldBe "o-1"
            raw.messageAttributes()["kind"]?.stringValue() shouldBe "order"
            val envelope = sns.queues.receive(audit).single().body()
            withClue("SNS's JSON envelope, with the message and its topic in it: $envelope") {
                ("\"Type\":\"Notification\"" in envelope) shouldBe true
                ("\"Message\":\"o-1\"" in envelope) shouldBe true
                ("\"TopicArn\":\"${placed.arn}\"" in envelope) shouldBe true
                ("\"kind\":{\"Type\":\"String\",\"Value\":\"order\"}" in envelope) shouldBe true
            }
        }

        test("what was published is kept, whether or not anything is subscribed") { sns ->
            val quiet = sns.create("quiet")

            SnsPublisher(sns.client).publish(quiet.message("a"))
            SnsPublisher(sns.client).publish(quiet.message("b"))

            sns.published(quiet).map { it.body } shouldBe listOf("a", "b")
        }

        test("a batch answers per entry, and an entry SNS would refuse fails only itself") { sns ->
            val placed = sns.create("placed-in-batches")
            val billing = sns.queues.create("billing-in-batches")
            sns.subscribe(placed, billing)

            val outcomes = SnsPublisher(sns.client).publishAll(listOf("a", "", "c").map { placed.message(it) })

            outcomes.map { it is Either.Right<*> } shouldBe listOf(true, false, true)
            outcomes[1].shouldBeLeft {
                it.cause.shouldBeInstanceOf<SnsRefused> { refused ->
                    refused.code shouldBe
                        "InvalidParameter"
                }
            }
            sns.queues.receive(billing).map { it.body() }.sorted() shouldBe listOf("a", "c")
        }

        test("a batch of more than ten is refused whole, as SNS refuses it") { sns ->
            val placed = sns.create("placed-too-many")
            val request = software.amazon.awssdk.services.sns.model.PublishBatchRequest.builder()
                .topicArn(placed.arn)
                .publishBatchRequestEntries((1..11).map { entry(it) })
                .build()

            Either.catch { sns.client.publishBatch(request) }
                .shouldBeLeft { it.shouldBeInstanceOf<TooManyEntriesInBatchRequestException>() }
        }

        test("a FIFO topic needs a group id, and passes it and the order on to a FIFO queue") { sns ->
            val placed = sns.create("placed.fifo", fifo = true)
            val billing = sns.queues.create("billing.fifo", fifo = true)
            sns.subscribe(placed, billing)
            val missing = software.amazon.awssdk.services.sns.model.PublishRequest.builder()
                .topicArn(placed.arn).message("no group").build()

            Either.catch { sns.client.publish(missing) }
                .shouldBeLeft { it.shouldBeInstanceOf<InvalidParameterException>() }
            listOf("1", "2", "3").forEach { body ->
                SnsPublisher(sns.client).publish(placed.message(body, groupId = "customer-1", dedupId = body))
                    .shouldBeRight { published -> (published.sequenceNumber != null) shouldBe true }
            }
            sns.queues.receive(billing).map { it.body() } shouldBe listOf("1", "2", "3")
        }
    }

    private fun entry(id: Int) =
        software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry.builder().id("$id").message("$id").build()
}
