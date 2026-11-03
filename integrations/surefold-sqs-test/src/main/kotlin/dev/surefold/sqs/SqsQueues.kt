package dev.surefold.sqs

import dev.surefold.Resource
import dev.surefold.resource
import org.elasticmq.rest.sqs.SQSRestServer
import org.elasticmq.rest.sqs.SQSRestServerBuilder
import org.junit.jupiter.api.extension.AfterAllCallback
import org.junit.jupiter.api.extension.BeforeAllCallback
import org.junit.jupiter.api.extension.ExtensionContext
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.Message
import software.amazon.awssdk.services.sqs.model.QueueAttributeName
import java.net.URI
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** How many messages a queue holds: [visible] to the next receive, and [inFlight], received and not yet deleted. */
data class Depth(val visible: Int, val inFlight: Int)

/**
 * SQS in the test JVM, on ElasticMQ, so a test needs no Docker (spec 0288). A surefold-junit suite shares one as its
 * [sqsQueues] resource; a Jupiter test class registers one with `@RegisterExtension`, started before its tests and
 * stopped after them. [client] is an `SqsClient` pointed at it, closed on [stop].
 */
class SqsQueues : BeforeAllCallback, AfterAllCallback {

    private lateinit var server: SQSRestServer

    lateinit var endpoint: URI
        private set

    lateinit var client: SqsClient
        private set

    /** Starts ElasticMQ on a port the system picks, so suites in parallel do not share one. */
    fun start() {
        server = SQSRestServerBuilder.withInterface(HOST).withDynamicPort().start()
        endpoint = URI("http://$HOST:${server.waitUntilStarted().localAddress().port}")
        client = SqsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.EU_WEST_1)
            .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build()
    }

    fun stop() {
        try {
            client.close()
        } finally {
            server.stopAndWait()
        }
    }

    /** Jupiter's: starts the queue before the class's first test. */
    override fun beforeAll(context: ExtensionContext) = start()

    /** Jupiter's: stops the queue after the class's last test. */
    override fun afterAll(context: ExtensionContext) = stop()

    /**
     * A queue named [name], FIFO with content-based deduplication when [fifo], whose messages move to [deadLetters]
     * once received more than [maxReceives] times.
     */
    fun create(
        name: String,
        fifo: Boolean = false,
        visibility: Duration = 30.seconds,
        deadLetters: Queue? = null,
        maxReceives: Int = 3,
    ): Queue {
        val attributes = buildMap {
            put(QueueAttributeName.VISIBILITY_TIMEOUT, visibility.inWholeSeconds.toString())
            if (fifo) {
                put(QueueAttributeName.FIFO_QUEUE, "true")
                put(QueueAttributeName.CONTENT_BASED_DEDUPLICATION, "true")
            }
            if (deadLetters != null) {
                val arn = attribute(deadLetters, QueueAttributeName.QUEUE_ARN)
                val policy = """{"deadLetterTargetArn":"$arn","maxReceiveCount":"$maxReceives"}"""
                put(QueueAttributeName.REDRIVE_POLICY, policy)
            }
        }
        return Queue(client.createQueue { it.queueName(name).attributes(attributes) }.queueUrl())
    }

    /** [bodies] sent to [queue], in order; on a FIFO queue, all in one group. */
    fun send(queue: Queue, vararg bodies: String) = bodies.forEach { body ->
        client.sendMessage { request ->
            request.queueUrl(queue.url).messageBody(body)
            if (queue.fifo) request.messageGroupId(GROUP)
        }
    }

    /** Up to ten messages on [queue], left visible: each receive counts towards its redrive, and none is deleted. */
    fun receive(queue: Queue): List<Message> =
        client.receiveMessage { request ->
            request.queueUrl(queue.url).maxNumberOfMessages(MAX_RECEIVE).visibilityTimeout(0)
        }.messages()

    fun depth(queue: Queue): Depth = Depth(
        visible = attribute(queue, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES).toInt(),
        inFlight = attribute(queue, QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE).toInt(),
    )

    private fun attribute(queue: Queue, name: QueueAttributeName): String =
        client.getQueueAttributes { it.queueUrl(queue.url).attributeNames(name) }.attributes().getValue(name)

    private companion object {
        const val HOST = "localhost"
        const val GROUP = "surefold-test"
        const val MAX_RECEIVE = 10
    }
}

/** An [SqsQueues], started when acquired and stopped on release: the shared resource of a surefold-junit suite. */
fun sqsQueues(): Resource<SqsQueues> = resource {
    install({ SqsQueues().also { it.start() } }) { queues, _ -> queues.stop() }
}
