package dev.surefold.kafka

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeEmpty
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-kafka-test may put on a test classpath: surefold-kafka, embedded-kafka, Jupiter's API, and what they
 * bring.
 */
class NoOtherDependenciesTest {

    private fun jars(property: String): List<String> {
        val raw = System.getProperty(property).orEmpty().split(File.pathSeparator).filter { it.isNotBlank() }
        withClue("the build must pass -D$property; see surefold-kafka-test/build.gradle.kts") { raw.shouldNotBeEmpty() }
        return raw
    }

    @Test
    fun `the main runtime classpath is surefold-kafka, embedded-kafka and Jupiter's API, and nothing beside them`() {
        val allowed = jars("surefold.kafka.test.allowedClasspath").toSet()
        val unexpected = jars("surefold.kafka.test.runtimeClasspath").filterNot { it in allowed }

        withClue(
            "surefold-kafka-test must stay surefold-kafka, embedded-kafka and Jupiter's API, but found: $unexpected",
        ) {
            unexpected.shouldBeEmpty()
        }
    }
}
