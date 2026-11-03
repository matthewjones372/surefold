package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.stream.Stream
import dev.surefold.stream.groupedWithin
import dev.surefold.stream.mapConcat
import dev.surefold.stream.mapPar
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sqs.model.MessageAttributeValue
import software.amazon.awssdk.services.sqs.model.SendMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.SendMessageRequest
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** A queue by its URL, so a send's arguments cannot be mistaken for a body or a group id. */
data class Queue(val url: String) {
    /** The last segment of [url], which is what the meters tag a queue with (spec 0289). */
    val name: String get() = url.substringAfterLast('/')

    /** SQS's own rule: a FIFO queue's name ends in `.fifo`. */
    val fifo: Boolean get() = url.endsWith(".fifo")
}

/** A message for [queue]; [groupId] and [dedupId] are for a FIFO queue only. */
data class SqsMessage(
    val queue: Queue,
    val body: String,
    val attributes: Map<String, String> = emptyMap(),
    val groupId: String? = null,
    val dedupId: String? = null,
)

/** A message for this queue; a group or deduplication id on a standard queue is refused here, not by SQS later. */
fun Queue.message(
    body: String,
    attributes: Map<String, String> = emptyMap(),
    groupId: String? = null,
    dedupId: String? = null,
): SqsMessage {
    require(fifo || (groupId == null && dedupId == null)) { "$url is a standard queue: no group or deduplication id" }
    return SqsMessage(this, body, attributes, groupId, dedupId)
}

/** What SQS answered for a message it accepted: [sequenceNumber] on a FIFO queue only. */
data class Sent(val messageId: String, val sequenceNumber: String?)

/** A message SQS did not take, and why: an [SqsRefused] for one it refused, or what the SDK threw. */
class SendFailed internal constructor(val message: SqsMessage, val cause: Throwable) {
    override fun toString(): String = "SendFailed(${message.queue.url}: $cause)"
}

/** One entry of a batch SQS refused, with its error [code]; [senderFault] says the message itself was wrong. */
class SqsRefused internal constructor(val code: String, val senderFault: Boolean, detail: String?) :
    Exception("$code${detail?.let { ": $it" }.orEmpty()}")

/** [message] sent and waited on: `Left` for one the SDK failed after its own retries. */
fun SqsClient.send(message: SqsMessage): Either<SendFailed, Sent> = send(message, ProducerMeters.here())

private fun SqsClient.send(message: SqsMessage, meters: ProducerMeters): Either<SendFailed, Sent> {
    val started = System.nanoTime()
    return Either.catchOrThrow<SdkException, Sent> {
        val response = sendMessage(message.request())
        Sent(response.messageId(), response.sequenceNumber())
    }.mapLeft { thrown -> SendFailed(message, thrown) }
        .also { outcome -> meters.sent(message.queue, started, listOf(outcome)) }
}

/**
 * Up to ten [messages], one `SendMessageBatch` per queue among them, answered per message in order: SQS reports each
 * entry on its own, so one it refuses fails only itself. A request the SDK fails is a `Left` for each of its messages.
 */
fun SqsClient.sendAll(messages: List<SqsMessage>): List<Either<SendFailed, Sent>> =
    sendAll(messages, ProducerMeters.here())

private fun SqsClient.sendAll(messages: List<SqsMessage>, meters: ProducerMeters): List<Either<SendFailed, Sent>> {
    require(messages.size <= MAX_BATCH) { "SQS takes up to $MAX_BATCH in a batch, and was given ${messages.size}" }
    val byIndex = messages.indices.groupBy { messages[it].queue }.flatMap { (queue, indices) ->
        val started = System.nanoTime()
        val outcomes = sendBatch(queue, indices.map { messages[it] })
        meters.sent(queue, started, outcomes)
        indices.zip(outcomes)
    }.toMap()
    return messages.indices.map(byIndex::getValue)
}

/**
 * Each element sent as the message [to] makes of it, and passed on, in order, once SQS has it. Up to [inFlight] sends
 * run at once, so a FIFO queue keeps a group's order only at 1. A message SQS does not take is a defect, for
 * `restartOnDefect`.
 */
fun <E, A : Any> Stream<E, A>.sendTo(sqs: SqsClient, inFlight: Int = IN_FLIGHT, to: (A) -> SqsMessage): Stream<E, A> {
    val meters = ProducerMeters.here()
    return mapPar(inFlight) { element -> sqs.send(to(element), meters).fold({ throw it.cause }, { element }) }
}

/**
 * Elements in batches of up to [batch], or what came within [within], each batch sent through [sendAll] and each
 * element passed on as the outcome of its own message. Nothing here fails the stream; what comes after decides.
 */
fun <E, A : Any> Stream<E, A>.sendBatch(
    sqs: SqsClient,
    batch: Int = MAX_BATCH,
    within: Duration = WITHIN,
    to: (A) -> SqsMessage,
): Stream<E, Either<SendFailed, A>> {
    require(batch in 1..MAX_BATCH) { "SQS takes up to $MAX_BATCH in a batch, and was given $batch" }
    val meters = ProducerMeters.here()
    return groupedWithin(batch, within)
        // One batch at a time, off the stream's own thread, because sendAll blocks.
        .mapPar(1) { taken: List<A> -> taken.zip(sqs.sendAll(taken.map(to), meters)) }
        .mapConcat { sent -> sent.map { (element, outcome) -> outcome.map { element } } }
}

private fun SqsClient.sendBatch(queue: Queue, messages: List<SqsMessage>): List<Either<SendFailed, Sent>> =
    Either.catchOrThrow<SdkException, Map<String, Either<Throwable, Sent>>> {
        val response = sendMessageBatch { it.queueUrl(queue.url).entries(messages.mapIndexed { id, m -> m.entry(id) }) }
        response.successful().associate { it.id() to Either.Right(Sent(it.messageId(), it.sequenceNumber())) } +
            response.failed().associate { it.id() to Either.Left(it.refused()) }
    }.fold(
        { thrown -> messages.map { Either.Left(SendFailed(it, thrown)) } },
        { byId ->
            messages.mapIndexed { id, message ->
                val outcome = byId[id.toString()] ?: Either.Left(SqsRefused("NoResult", false, "SQS reported nothing"))
                outcome.mapLeft { SendFailed(message, it) }
            }
        },
    )

private fun BatchResultErrorEntry.refused() = SqsRefused(code(), senderFault() ?: false, message())

private fun SqsMessage.request(): SendMessageRequest = SendMessageRequest.builder()
    .queueUrl(queue.url)
    .messageBody(body)
    .messageAttributes(attributes.mapValues { (_, value) -> value.attribute() })
    .messageGroupId(groupId)
    .messageDeduplicationId(dedupId)
    .build()

private fun SqsMessage.entry(id: Int): SendMessageBatchRequestEntry = SendMessageBatchRequestEntry.builder()
    .id(id.toString())
    .messageBody(body)
    .messageAttributes(attributes.mapValues { (_, value) -> value.attribute() })
    .messageGroupId(groupId)
    .messageDeduplicationId(dedupId)
    .build()

private fun String.attribute() = MessageAttributeValue.builder().dataType("String").stringValue(this).build()

/** SQS's own limit on a `SendMessageBatch`. */
const val MAX_BATCH: Int = 10

private const val IN_FLIGHT = 16

private val WITHIN = 100.milliseconds
