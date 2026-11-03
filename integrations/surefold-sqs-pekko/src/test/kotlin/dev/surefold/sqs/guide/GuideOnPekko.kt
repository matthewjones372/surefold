package dev.surefold.sqs.guide

import dev.surefold.sqs.Queue
import dev.surefold.sqs.Sqs
import dev.surefold.sqs.mapMessage
import dev.surefold.sqs.runDeleting
import dev.surefold.sqs.source
import dev.surefold.stream.PekkoStreams
import dev.surefold.stream.Running
import dev.surefold.stream.start
import org.apache.pekko.Done
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.stream.connectors.awsspi.PekkoHttpClient
import org.apache.pekko.stream.connectors.sqs.SqsSourceSettings
import software.amazon.awssdk.services.sqs.SqsAsyncClient

fun onPekkoHttp(system: ActorSystem): SqsAsyncClient =
    SqsAsyncClient.builder().httpClient(PekkoHttpClient.builder().withActorSystem(system).build()).build()

fun shipping(
    system: ActorSystem,
    client: SqsAsyncClient,
    orders: Queue,
    ship: (String) -> Unit,
): Running<Nothing, Done> =
    Sqs.source(client, orders, SqsSourceSettings.create().withParallelRequests(4))
        .mapMessage { message -> ship(message.body()) }
        .runDeleting(client, orders)
        .start(PekkoStreams(system))
