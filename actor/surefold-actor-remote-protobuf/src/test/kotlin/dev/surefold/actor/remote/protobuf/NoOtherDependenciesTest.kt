package dev.surefold.actor.remote.protobuf

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-actor-remote-protobuf may put on a service's classpath: surefold-actor-remote's own, and Protobuf. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "protobuf-java-")

    @Test
    fun `the main runtime classpath is surefold-actor-remote and Protobuf, and nothing else`() {
        val raw = System.getProperty("surefold.actor.remote.protobuf.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.remote.protobuf.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue(
            "surefold-actor-remote-protobuf must stay surefold-actor-remote and Protobuf, but found: $unexpected",
        ) {
            unexpected.shouldBeEmpty()
        }
    }
}
