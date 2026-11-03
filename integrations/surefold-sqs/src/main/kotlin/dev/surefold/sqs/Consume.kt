package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.fail.onLeft
import dev.surefold.logWarn
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.blocking
import dev.surefold.stream.map
import dev.surefold.stream.runFold
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.DeleteMessageBatchRequestEntry
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** Where a read starts: [consume] here. */
object Sqs

/** An element and the receipt of the message it came from; the message is deleted by `runDeleting`. */
class Received<out A : Any> internal constructor(val value: A, internal val receipt: Receipt)

/** A message's receipt handle, settled from whichever thread its element reaches the end, or is dropped, on. */
internal class Receipt(private val handle: String, private val reader: Settling) {
    fun handled() = reader.handled(handle)

    fun dropped(nack: Boolean) = reader.dropped(handle, nack)
}

internal interface Settling {
    fun handled(handle: String)

    fun dropped(handle: String, nack: Boolean)
}

/** For a module that maps received elements in batches of its own, such as surefold-sns; not for a pipeline. */
@RequiresOptIn("A seam for a module that carries an SQS receipt through operators of its own.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION)
annotation class SqsSpi

/** [value] in place of this element's, with its message's receipt carried through. */
@SqsSpi
fun <B : Any> Received<*>.carrying(value: B): Received<B> = Received(value, receipt)

/**
 * The messages on [queue], read by long polls of up to [waitTime] for up to [maxMessages] at a time, each with every
 * attribute. Nothing is deleted until its element reaches the end of a run that ends in [runDeleting]; a message
 * that never does comes back after its [visibility] timeout, and past its `maxReceiveCount` goes to the queue's own
 * dead-letter queue. [sqs] is the caller's, and is not closed.
 *
 * With [extendVisibility], every message handed out and not yet settled has its timeout renewed every half
 * [visibility], so a handler slower than the timeout does not see its message twice.
 */
fun Sqs.consume(
    sqs: SqsClient,
    queue: Queue,
    waitTime: Duration = 20.seconds,
    maxMessages: Int = MAX_BATCH,
    visibility: Duration = 30.seconds,
    extendVisibility: Boolean = true,
): Stream<Nothing, Received<Message>> {
    require(waitTime in Duration.ZERO..20.seconds) { "SQS waits up to 20 seconds, and was asked for $waitTime" }
    require(maxMessages in 1..MAX_BATCH) { "SQS answers up to $MAX_BATCH at a time, and was asked for $maxMessages" }
    require(visibility in (if (extendVisibility) 1.seconds else Duration.ZERO)..12.hours) {
        "SQS hides a message for up to 12 hours, and at least a second when it is extended, not $visibility"
    }
    val request = ReceiveMessageRequest.builder()
        .queueUrl(queue.url)
        .waitTimeSeconds(waitTime.inWholeSeconds.toInt())
        .maxNumberOfMessages(maxMessages)
        .visibilityTimeout(visibility.inWholeSeconds.toInt())
        .messageAttributeNames("All")
        .messageSystemAttributeNamesWithStrings("All")
        .build()
    return Stream.blocking(
        open = { Reader(sqs, queue, request, Extender(sqs, queue, visibility).takeIf { extendVisibility }) },
        next = { reader -> reader.next() },
        ready = { reader -> reader.ready() },
        wake = { reader -> reader.wake() },
        close = { reader -> reader.close() },
    )
}

/** A run described that deletes each message once its element reaches the end, answering how many did. */
fun <E> Stream<E, Received<*>>.runDeleting(): Run<E, Long> =
    map { element ->
        element.receipt.handled()
        1L
    }.runFold(0L, Long::plus)

/** One run's reads of a queue; what was handled is deleted before each receive, and on close. */
private class Reader(
    private val sqs: SqsClient,
    private val queue: Queue,
    private val request: ReceiveMessageRequest,
    private val extender: Extender?,
) : Settling {
    private val received = ArrayDeque<Message>()
    private val handled = ConcurrentLinkedQueue<String>()

    @Volatile private var receiving: Thread? = null

    @Volatile private var stopping = false

    fun next(): Received<Message>? {
        while (received.isEmpty()) {
            if (stopping) return null
            deleteHandled()
            receiving = Thread.currentThread()
            try {
                received.addAll(sqs.receiveMessage(request).messages())
            } finally {
                receiving = null
            }
        }
        val message = received.removeFirst()
        extender?.hold(message.receiptHandle())
        return Received(message, Receipt(message.receiptHandle(), this))
    }

    override fun handled(handle: String) {
        extender?.release(handle)
        handled += handle
    }

    override fun dropped(handle: String, nack: Boolean) {
        extender?.release(handle)
        if (nack) {
            // A nack SQS fails leaves the message to come back after its timeout, as it would without one.
            Either.catchOrThrow<SdkException, Unit> {
                sqs.changeMessageVisibility { it.queueUrl(queue.url).receiptHandle(handle).visibilityTimeout(0) }
            }.onLeft { failed -> logWarn("surefold-sqs: a nack on ${queue.url} failed: $failed") }
        }
    }

    fun ready() = received.isNotEmpty()

    // A long poll waits up to twenty seconds for a message; the interrupt ends it now, on a virtual thread.
    fun wake() {
        stopping = true
        receiving?.interrupt()
    }

    fun close() {
        extender?.stop()
        // A stop's interrupt may still be set, and would fail the last delete.
        val interrupted = Thread.interrupted()
        try {
            deleteHandled()
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    // An entry SQS fails to delete comes back after its visibility timeout, as it would after a crash: at least once.
    private fun deleteHandled() {
        generateSequence { handled.poll() }.toList().chunked(MAX_BATCH).forEach { handles ->
            val entries = handles.mapIndexed { id, handle ->
                DeleteMessageBatchRequestEntry.builder().id(id.toString()).receiptHandle(handle).build()
            }
            sqs.deleteMessageBatch { it.queueUrl(queue.url).entries(entries) }
        }
    }
}
