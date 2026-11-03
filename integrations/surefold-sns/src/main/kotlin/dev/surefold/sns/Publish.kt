package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.mapLeft
import dev.surefold.stream.Stream
import dev.surefold.stream.mapPar
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.MessageAttributeValue
import software.amazon.awssdk.services.sns.model.PublishRequest

/** A topic by its ARN, so a publish's arguments cannot be mistaken for a body or a group id. */
data class SnsTopic(val arn: String) {
    /** SNS's own rule: a FIFO topic's name ends in `.fifo`. */
    val fifo: Boolean get() = arn.endsWith(".fifo")
}

/** A message for [topic]; [groupId] and [dedupId] are for a FIFO topic only. */
data class SnsMessage(
    val topic: SnsTopic,
    val body: String,
    val attributes: Map<String, String> = emptyMap(),
    val groupId: String? = null,
    val dedupId: String? = null,
)

/** A message for this topic; a group or deduplication id on a standard topic is refused here, not by SNS later. */
fun SnsTopic.message(
    body: String,
    attributes: Map<String, String> = emptyMap(),
    groupId: String? = null,
    dedupId: String? = null,
): SnsMessage {
    require(fifo || (groupId == null && dedupId == null)) { "$arn is a standard topic: no group or deduplication id" }
    return SnsMessage(this, body, attributes, groupId, dedupId)
}

/** What SNS answered for a message it accepted: [sequenceNumber] on a FIFO topic only. */
data class Published(val messageId: String, val sequenceNumber: String?)

/** A message SNS did not take, and why: what the SDK threw after its own retries. */
class PublishFailed internal constructor(val message: SnsMessage, val cause: Throwable) {
    override fun toString(): String = "PublishFailed(${message.topic.arn}: $cause)"
}

/** Publishes through [client], which stays the caller's: closing it is theirs. */
class SnsPublisher(private val client: SnsClient) {

    /** [message] published and waited on: `Left` for one the SDK failed after its own retries. */
    fun publish(message: SnsMessage): Either<PublishFailed, Published> =
        Either.catchOrThrow<SdkException, Published> {
            val response = client.publish(message.request())
            Published(response.messageId(), response.sequenceNumber())
        }.mapLeft { thrown -> PublishFailed(message, thrown) }
}

/**
 * Each element published as the message [to] makes of it, and passed on, in order, once SNS has it. Up to [inFlight]
 * publishes run at once, so a FIFO topic keeps a group's order only at 1. A message SNS does not take is a defect, for
 * `restartOnDefect`.
 */
fun <E, A : Any> Stream<E, A>.publishTo(
    publisher: SnsPublisher,
    inFlight: Int = IN_FLIGHT,
    to: (A) -> SnsMessage,
): Stream<E, A> =
    mapPar(inFlight) { element -> publisher.publish(to(element)).fold({ failed -> throw failed.cause }, { element }) }

internal fun String.attribute(): MessageAttributeValue =
    MessageAttributeValue.builder().dataType("String").stringValue(this).build()

private fun SnsMessage.request(): PublishRequest = PublishRequest.builder()
    .topicArn(topic.arn)
    .message(body)
    .messageAttributes(attributes.mapValues { (_, value) -> value.attribute() })
    .messageGroupId(groupId)
    .messageDeduplicationId(dedupId)
    .build()

private const val IN_FLIGHT = 16
