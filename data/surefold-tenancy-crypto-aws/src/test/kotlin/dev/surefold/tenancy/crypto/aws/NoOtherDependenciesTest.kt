package dev.surefold.tenancy.crypto.aws

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-tenancy-crypto-aws may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The AWS SDK's KMS client, the core it shares with every client, and the JDK HTTP client it runs on.
        "kms-", "auth-", "aws-core-", "aws-json-protocol-", "checksums-", "endpoints-spi-", "eventstream-",
        "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-", "metrics-spi-", "profiles-",
        "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-", "url-connection-client-",
        "utils-", "reactive-streams-", "slf4j-api-",
    )

    init {
        test("the main runtime classpath is surefold and the AWS SDK's KMS client, and nothing else") {
            val raw = System.getProperty("surefold.tenancy.crypto.aws.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.tenancy.crypto.aws.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
