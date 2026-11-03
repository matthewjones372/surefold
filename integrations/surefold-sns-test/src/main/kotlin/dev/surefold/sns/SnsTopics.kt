package dev.surefold.sns

import dev.surefold.Resource
import dev.surefold.resource
import dev.surefold.sqs.Queue
import dev.surefold.sqs.SqsQueues
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sns.model.InvalidParameterException
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.NotFoundException
import software.amazon.awssdk.services.sns.model.PublishBatchRequest
import software.amazon.awssdk.services.sns.model.PublishBatchResponse
import software.amazon.awssdk.services.sns.model.PublishBatchResultEntry
import software.amazon.awssdk.services.sns.model.PublishRequest
import software.amazon.awssdk.services.sns.model.PublishResponse
import software.amazon.awssdk.services.sns.model.TooManyEntriesInBatchRequestException
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue as QueueAttribute

/**
 * SNS topics in the test JVM, delivering to queues on [queues], so a test needs no Docker (spec 0291). [client] is an
 * `SnsClient` that answers `publish` and `publishBatch` as SNS does for what a caller can get wrong, and delivers each
 * message to every queue subscribed, raw or in SNS's JSON envelope. It is not SNS's wire protocol: nothing is signed
 * or serialised. A surefold-junit suite shares one as its [snsTopics] resource; a Jupiter test class registers one with
 * `@RegisterExtension`.
 */
class SnsTopics : BeforeAllCallback, AfterAllCallback {

    /** The queues a topic delivers to, started and stopped with it. */
    val queues: SqsQueues = SqsQueues()

    private val topics = ConcurrentHashMap<String, Topic>()

    private val sequence = AtomicLong()

    val client: SnsClient = object : SnsClient {
        override fun publish(request: PublishRequest): PublishResponse {
            val topic = topicOf(request.topicArn())
            refusal(topic, request.message(), request.messageGroupId())?.let { reason ->
                throw InvalidParameterException.builder().message(reason).build()
            }
            val (id, number) = topic.deliver(request.message(), request.messageAttributes(), request.messageGroupId())
            return PublishResponse.builder().messageId(id).sequenceNumber(number).build()
        }

        override fun publishBatch(request: PublishBatchRequest): PublishBatchResponse {
            val entries = request.publishBatchRequestEntries()
            if (entries.size > MAX_BATCH) {
                throw TooManyEntriesInBatchRequestException.builder().message("${entries.size} entries").build()
            }
            val topic = topicOf(request.topicArn())
            val (refused, taken) = entries.partition { refusal(topic, it.message(), it.messageGroupId()) != null }
            return PublishBatchResponse.builder()
                .successful(
                    taken.map { entry ->
                        val (id, number) =
                            topic.deliver(entry.message(), entry.messageAttributes(), entry.messageGroupId())
                        PublishBatchResultEntry.builder().id(entry.id()).messageId(id).sequenceNumber(number).build()
                    },
                )
                .failed(
                    refused.map { entry ->
                        BatchResultErrorEntry.builder().id(entry.id()).code(INVALID).senderFault(true)
                            .message(refusal(topic, entry.message(), entry.messageGroupId())).build()
                    },
                )
                .build()
        }

        override fun serviceName() = "sns"

        override fun close() = Unit
    }

    fun start() = queues.start()

    fun stop() = queues.stop()

    /** Jupiter's: starts the queues before the class's first test. */
    override fun beforeAll(context: ExtensionContext) = start()

    /** Jupiter's: stops the queues after the class's last test. */
    override fun afterAll(context: ExtensionContext) = stop()

    /** A topic named [name]; a FIFO one's name must end in `.fifo`, as SNS's must. */
    fun create(name: String, fifo: Boolean = false): SnsTopic {
        require(fifo == name.endsWith(".fifo")) { "a FIFO topic's name, and only a FIFO topic's, ends in .fifo" }
        val arn = "arn:aws:sns:$REGION:$ACCOUNT:$name"
        topics.computeIfAbsent(arn) { Topic(SnsTopic(arn)) }
        return SnsTopic(arn)
    }

    /** [queue] subscribed to [topic]: given each message as published with [raw], or else in SNS's JSON envelope. */
    fun subscribe(topic: SnsTopic, queue: Queue, raw: Boolean = true) {
        topicOf(topic.arn).subscriptions += Subscription(queue, raw)
    }

    /** What was published to [topic], in order, whether or not anything was subscribed. */
    fun published(topic: SnsTopic): List<SnsMessage> = topicOf(topic.arn).published.toList()

    private fun topicOf(arn: String): Topic =
        topics[arn] ?: throw NotFoundException.builder().message("no topic $arn").build()

    /** Why SNS would refuse this message, for the refusals a caller can cause; null where it would take it. */
    private fun refusal(topic: Topic, body: String?, groupId: String?): String? = when {
        body.isNullOrEmpty() -> "Empty message"
        topic.topic.fifo && groupId == null -> "The MessageGroupId parameter is required for FIFO topics"
        !topic.topic.fifo && groupId != null -> "The MessageGroupId parameter is supported only for FIFO topics"
        else -> null
    }

    private inner class Topic(val topic: SnsTopic) {
        val subscriptions = CopyOnWriteArrayList<Subscription>()
        val published = CopyOnWriteArrayList<SnsMessage>()

        /** Kept and delivered to every subscriber; its id, and a sequence number on a FIFO topic. */
        fun deliver(
            body: String,
            attributes: Map<String, MessageAttributeValue>,
            groupId: String?,
        ): Pair<String, String?> {
            val id = UUID.randomUUID().toString()
            val strings = attributes.mapValues { (_, value) -> value.stringValue() }
            published += SnsMessage(topic, body, strings, groupId)
            subscriptions.forEach { subscription ->
                queues.client.sendMessage { request ->
                    request.queueUrl(subscription.queue.url)
                        .messageBody(if (subscription.raw) body else envelope(id, body, strings))
                    if (subscription.raw) request.messageAttributes(strings.mapValues { it.value.attribute() })
                    if (subscription.queue.fifo) request.messageGroupId(groupId ?: id)
                }
            }
            return id to if (topic.fifo) sequence.incrementAndGet().toString().padStart(SEQUENCE_DIGITS, '0') else null
        }

        private fun envelope(id: String, body: String, attributes: Map<String, String>): String {
            val fields = listOf(
                "Type" to "Notification",
                "MessageId" to id,
                "TopicArn" to topic.arn,
                "Message" to body,
                "Timestamp" to Instant.now().toString(),
            ).joinToString(",") { (name, value) -> "\"$name\":${json(value)}" }
            val attributeFields = attributes.entries.joinToString(",") { (name, value) ->
                "${json(name)}:{\"Type\":\"String\",\"Value\":${json(value)}}"
            }
            return "{$fields,\"MessageAttributes\":{$attributeFields}}"
        }
    }

    private class Subscription(val queue: Queue, val raw: Boolean)

    private companion object {
        const val MAX_BATCH = 10
        const val INVALID = "InvalidParameter"
        const val REGION = "eu-west-1"
        const val ACCOUNT = "000000000000"
        const val SEQUENCE_DIGITS = 20
    }
}

/** An [SnsTopics], started when acquired and stopped on release: the shared resource of a surefold-junit suite. */
fun snsTopics(): Resource<SnsTopics> = resource {
    install({ SnsTopics().also { it.start() } }) { topics, _ -> topics.stop() }
}

private fun String.attribute(): QueueAttribute = QueueAttribute.builder().dataType("String").stringValue(this).build()

/** [value] as a JSON string, escaped as JSON requires. */
private fun json(value: String): String = buildString {
    append('"')
    value.forEach { c ->
        when {
            c == '"' -> append("\\\"")
            c == '\\' -> append("\\\\")
            c == '\n' -> append("\\n")
            c == '\r' -> append("\\r")
            c == '\t' -> append("\\t")
            c < ' ' -> append("\\u").append(c.code.toString(HEX).padStart(UNICODE_DIGITS, '0'))
            else -> append(c)
        }
    }
    append('"')
}

private const val HEX = 16
private const val UNICODE_DIGITS = 4
