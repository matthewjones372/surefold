package dev.surefold.actor.remote.avro

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-actor-remote-avro may put on a service's classpath: surefold-actor-remote's own, and Avro. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // Avro, and the JSON, compression and logging libraries it declares.
        "avro-",
        "jackson-",
        "commons-codec-",
        "commons-io-",
        "commons-compress-",
        "commons-lang3-",
        "slf4j-api-",
    )

    @Test
    fun `the main runtime classpath is surefold-actor-remote and Avro, and nothing else`() {
        val raw = System.getProperty("surefold.actor.remote.avro.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.remote.avro.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-remote-avro must stay surefold-actor-remote and Avro, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
