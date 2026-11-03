package dev.surefold.sqs

import dev.surefold.Resource
import dev.surefold.resource
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.stream.connectors.awsspi.PekkoHttpClient
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsAsyncClient

/**
 * ElasticMQ, the actor system the connector runs on, and an async client on the connector's own HTTP transport,
 * installed together as a suite's shared resource, which terminates the system on release.
 */
class PekkoSqsKit internal constructor(
    val sqs: SqsQueues,
    val system: ClassicActorSystemProvider,
    val client: SqsAsyncClient,
)

fun pekkoSqsKit(name: String): Resource<PekkoSqsKit> = resource {
    val sqs = sqsQueues().bind()
    val system = install({ ActorSystem.create(name) }) { system, _ ->
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }
    val client = install({
        SqsAsyncClient.builder()
            .endpointOverride(sqs.endpoint)
            .region(Region.EU_WEST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .httpClient(PekkoHttpClient.builder().withActorSystem(system).build())
            .build()
    }) { client, _ -> client.close() }
    PekkoSqsKit(sqs, system, client)
}
