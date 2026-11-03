@file:OptIn(SqsSpi::class)

package dev.surefold.sns

import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.sqs.Received
import dev.surefold.sqs.SqsSpi
import dev.surefold.sqs.carrying
import dev.surefold.stream.Stream
import dev.surefold.stream.groupedWithin
import dev.surefold.stream.mapConcat
import dev.surefold.stream.mapPar
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sns.SnsClient
import software.amazon.awssdk.services.sns.model.BatchResultErrorEntry
import software.amazon.awssdk.services.sns.model.PublishBatchRequestEntry
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One entry of a batch SNS refused, with its error [code]; [senderFault] says the message itself was wrong. */
class SnsRefused internal constructor(val code: String, val senderFault: Boolean, detail: String?) :
    Exception("$code${detail?.let { ": $it" }.orEmpty()}")

/**
 * Elements in batches of up to [batch], or what came within [within], each batch published through
 * [SnsPublisher.publishAll] and each element passed on as the outcome of its own message. Nothing here fails the
 * stream; what comes after decides.
 */
fun <E, A : Any> Stream<E, A>.publishBatch(
    publisher: SnsPublisher,
    batch: Int = MAX_BATCH,
    within: Duration = WITHIN,
    to: (A) -> SnsMessage,
): Stream<E, Either<PublishFailed, A>> =
    batched(batch, within) { taken: List<A> -> taken.zip(publisher.publishAll(taken.map(to))) }
        .mapConcat { sent -> sent.map { (element, outcome) -> outcome.map { element } } }

/**
 * [publishBatch] over messages read from SQS, each element carrying its receipt, and named apart from it because the
 * two overloads cannot be told apart. A message is deleted by
 * `runDeleting` only once its own publish succeeded, and one whose publish failed is a `Left` for `divertLefts` or
 * `absolve` to decide on.
 */
fun <E, A : Any> Stream<E, Received<A>>.publishMessageBatch(
    publisher: SnsPublisher,
    batch: Int = MAX_BATCH,
    within: Duration = WITHIN,
    to: (A) -> SnsMessage,
): Stream<E, Received<Either<PublishFailed, A>>> =
    batched(batch, within) { taken: List<Received<A>> -> taken.zip(publisher.publishAll(taken.map { to(it.value) })) }
        .mapConcat { sent -> sent.map { (r, outcome) -> r.carrying(outcome.map { r.value }) } }

private fun <E, A : Any, B : Any> Stream<E, A>.batched(
    batch: Int,
    within: Duration,
    publish: (List<A>) -> B,
): Stream<E, B> {
    require(batch in 1..MAX_BATCH) { "SNS takes up to $MAX_BATCH in a batch, and was given $batch" }
    // One batch at a time, off the stream's own thread, because publishAll blocks.
    return groupedWithin(batch, within).mapPar(1) { taken -> publish(taken) }
}

internal fun SnsClient.publishBatch(
    topic: SnsTopic,
    messages: List<SnsMessage>,
): List<Either<PublishFailed, Published>> =
    Either.catchOrThrow<SdkException, Map<String, Either<Throwable, Published>>> {
        val entries = messages.mapIndexed { id, message -> message.entry(id) }
        val response = publishBatch { it.topicArn(topic.arn).publishBatchRequestEntries(entries) }
        response.successful().associate { it.id() to Either.Right(Published(it.messageId(), it.sequenceNumber())) } +
            response.failed().associate { it.id() to Either.Left(it.refused()) }
    }.fold(
        { thrown -> messages.map { Either.Left(PublishFailed(it, thrown)) } },
        { byId ->
            messages.mapIndexed { id, message ->
                val outcome = byId[id.toString()] ?: Either.Left(SnsRefused("NoResult", false, "SNS reported nothing"))
                outcome.mapLeft { PublishFailed(message, it) }
            }
        },
    )

private fun BatchResultErrorEntry.refused() = SnsRefused(code(), senderFault() ?: false, message())

private fun SnsMessage.entry(id: Int): PublishBatchRequestEntry = PublishBatchRequestEntry.builder()
    .id(id.toString())
    .message(body)
    .messageAttributes(attributes.mapValues { (_, value) -> value.attribute() })
    .messageGroupId(groupId)
    .messageDeduplicationId(dedupId)
    .build()

/** SNS's own limit on a `PublishBatch`. */
const val MAX_BATCH: Int = 10

private val WITHIN = 100.milliseconds
