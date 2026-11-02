package io.github.matthewjones372.surefold.files.test

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-files-test may put on a service's test classpath: surefold-files', and Testcontainers'. */
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
        // Testcontainers and what it brings.
        "testcontainers-", "docker-java-", "duct-tape-", "commons-compress-", "commons-io-", "commons-lang3-",
        "jna-", "junit-", "hamcrest-", "commons-codec-", "jackson-annotations-",
    )

    init {
        test("the main runtime classpath is surefold-files and Testcontainers, and nothing else") {
            val raw = System.getProperty("surefold.files.test.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.files.test.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
