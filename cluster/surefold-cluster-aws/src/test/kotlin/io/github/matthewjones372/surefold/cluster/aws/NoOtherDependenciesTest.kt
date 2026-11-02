package io.github.matthewjones372.surefold.cluster.aws

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-cluster-aws may put on a service's classpath: surefold-cluster's own, and three clients of the AWS SDK.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The AWS SDK's three clients, the core they share, and the JDK HTTP client they run on.
        "servicediscovery-",
        "ecs-",
        "dynamodb-",
        "url-connection-client-",
        "auth-",
        "aws-core-",
        "aws-json-protocol-",
        "checksums-",
        "endpoints-spi-",
        "http-auth-",
        "http-client-spi-",
        "identity-spi-",
        "json-utils-",
        "metrics-spi-",
        "profiles-",
        "protocol-core-",
        "regions-",
        "retries-",
        "sdk-core-",
        "third-party-jackson-core-",
        "utils-",
        "eventstream-",
        "reactive-streams-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is surefold-cluster and the AWS SDK's clients, and nothing else`() {
        val raw = System.getProperty("surefold.cluster.aws.runtimeClasspath")
        withClue("the build must pass -Dsurefold.cluster.aws.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-cluster-aws must stay surefold-cluster and the AWS SDK's clients, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
