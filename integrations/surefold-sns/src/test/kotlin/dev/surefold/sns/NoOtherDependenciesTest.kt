package dev.surefold.sns

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-sns may put on a service's classpath: surefold-stream's own and the SDK's SNS client. No backend. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The AWS SDK's SNS client, the core it shares with every client, and the JDK HTTP client it runs on.
        "sns-", "auth-", "aws-core-", "aws-json-protocol-", "aws-query-protocol-", "checksums-", "endpoints-spi-",
        "eventstream-",
        "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-", "metrics-spi-", "profiles-",
        "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-", "url-connection-client-",
        "utils-", "reactive-streams-", "slf4j-api-",
    )

    init {
        test("the main runtime classpath is surefold-stream and the AWS SDK's SNS client, and nothing else") {
            val raw = System.getProperty("surefold.sns.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.sns.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
