package io.github.matthewjones372.surefold.kafka

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-kafka may put on a consumer's classpath: surefold-stream's own and the Kafka client. No backend. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        // surefold-stream and what it is allowed: surefold and the Kotlin standard library.
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The Kafka client, and the compression codecs and logging facade it declares.
        "kafka-clients-",
        "zstd-jni-",
        "lz4-java-",
        "snappy-java-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is surefold-stream and the Kafka client, and no backend`() {
        val raw = System.getProperty("surefold.kafka.runtimeClasspath")
        withClue("the build must pass -Dsurefold.kafka.runtimeClasspath; see surefold-kafka/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-kafka must stay surefold-stream and the Kafka client, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
