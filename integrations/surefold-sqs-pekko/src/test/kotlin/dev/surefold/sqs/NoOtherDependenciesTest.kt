package dev.surefold.sqs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-sqs-pekko may put on a classpath: surefold-sqs, Pekko, and the connector with its HTTP transport. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The AWS SDK's SQS client, the core it shares with every client, and the JDK HTTP client it runs on.
        "sqs-", "auth-", "aws-core-", "aws-json-protocol-", "aws-query-protocol-", "checksums-", "endpoints-spi-",
        "eventstream-",
        "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-", "metrics-spi-", "profiles-",
        "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-", "url-connection-client-",
        "utils-", "reactive-streams-", "slf4j-api-",
        // Pekko's streams and HTTP, which the connector and its async client's transport run on, and Scala's library.
        "pekko-", "scala-library-", "ssl-config-core_", "parboiled_", "config-",
    )

    init {
        test("the main runtime classpath is surefold-sqs, Pekko and its SQS connector, and nothing else") {
            val raw = System.getProperty("surefold.sqs.pekko.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.sqs.pekko.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
