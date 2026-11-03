@file:OptIn(SqsSpi::class, SourceSeam::class)

package dev.surefold.sqs

import dev.surefold.stream.Run
import dev.surefold.stream.SourceSeam
import dev.surefold.stream.Stream
import dev.surefold.stream.hooked
import dev.surefold.stream.map
import dev.surefold.stream.runWith
import org.apache.pekko.Done
import org.apache.pekko.stream.KillSwitches
import org.apache.pekko.stream.connectors.sqs.MessageAction
import org.apache.pekko.stream.connectors.sqs.SqsAckSettings
import org.apache.pekko.stream.connectors.sqs.SqsSourceSettings
import org.apache.pekko.stream.connectors.sqs.javadsl.SqsAckSink
import org.apache.pekko.stream.connectors.sqs.javadsl.SqsSource
import org.apache.pekko.stream.javadsl.Keep
import software.amazon.awssdk.services.sqs.SqsAsyncClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityRequest
import software.amazon.awssdk.services.sqs.model.Message

/**
 * The messages on [queue] through Pekko's SQS connector, which keeps up to the settings' parallel receives in flight
 * and buffers what they return. Pekko runs it and nothing else does; [Sqs.consume] runs on any backend. Each message
 * is deleted by [runDeleting] with a client and queue, through the connector's ack sink.
 *
 * A run's `stop()` drains: no receive is started after it, and what was already handed out finishes and is deleted
 * before the exit completes. [client] is the caller's, and is not closed.
 */
fun Sqs.source(
    client: SqsAsyncClient,
    queue: Queue,
    settings: SqsSourceSettings = SqsSourceSettings.create(),
): Stream<Nothing, Received<Message>> =
    Stream.hooked { hooks ->
        SqsSource.create(queue.url, settings, client)
            .viaMat(KillSwitches.single(), Keep.right())
            .mapMaterializedValue { switch -> hooks.onStop { switch.shutdown() } }
            .map { message -> Received(message, Connector(client, queue, message)) }
    }

/**
 * A run described that deletes each message from [Sqs.source] once its element reaches the end, through the
 * connector's ack sink, which batches and sends its deletes asynchronously.
 */
fun <E> Stream<E, Received<*>>.runDeleting(
    client: SqsAsyncClient,
    queue: Queue,
    settings: SqsAckSettings = SqsAckSettings.create(),
): Run<E, Done> =
    map { element -> MessageAction.delete(element.settle.message()) }
        .runWith(SqsAckSink.create(queue.url, settings, client))

/** A message from the connector, which its ack sink settles; a nack is a visibility change sent at once. */
private class Connector(val client: SqsAsyncClient, val queue: Queue, val message: Message) : Settle {
    override fun handled() = error(ONE_SOURCE_EACH)

    override fun dropped(nack: Boolean) {
        if (nack) {
            // Not waited on: a nack that fails leaves the message to come back after its timeout, as it would without.
            client.changeMessageVisibility(
                ChangeMessageVisibilityRequest.builder()
                    .queueUrl(queue.url)
                    .receiptHandle(message.receiptHandle())
                    .visibilityTimeout(0)
                    .build(),
            )
        }
    }
}

private fun Settle.message(): Message = (this as? Connector)?.message ?: error(ONE_SOURCE_EACH)

private const val ONE_SOURCE_EACH =
    "messages from Sqs.source end on runDeleting(client, queue), and messages from Sqs.consume on runDeleting()"
