package dev.surefold.kafka

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-kafka-pekko may put on a consumer's classpath: surefold-stream's own, and Pekko's Kafka connector
 * with the client it brings.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        // surefold-stream and everything it is allowed.
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "pekko-",
        "scala-library",
        "config-",
        "reactive-streams-",
        "ssl-config-core_",
        // The connector's client, and the compression codecs and logging facade the client declares.
        "kafka-clients-",
        "zstd-jni-",
        "lz4-java-",
        "snappy-java-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is surefold-stream and the Kafka connector, and nothing else`() {
        val raw = System.getProperty("surefold.kafka.runtimeClasspath")
        withClue("the build must pass -Dsurefold.kafka.runtimeClasspath; see surefold-kafka/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-kafka-pekko must stay surefold-stream and the Kafka connector, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
