package dev.surefold.files

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-files may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The AWS SDK's S3 client, the core it shares with every client, and the JDK HTTP client it runs on.
        "s3-", "arns-", "auth-", "aws-core-", "aws-query-protocol-", "aws-xml-protocol-", "checksums-", "crt-core-",
        "endpoints-spi-", "eventstream-", "http-auth-", "http-client-spi-", "identity-spi-", "json-utils-",
        "metrics-spi-", "profiles-", "protocol-core-", "regions-", "retries-", "sdk-core-", "third-party-jackson-core-",
        "url-connection-client-", "utils-", "reactive-streams-", "slf4j-api-",
    )

    init {
        test("the main runtime classpath is surefold and the AWS SDK's S3 client, and nothing else") {
            val raw = System.getProperty("surefold.files.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.files.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
