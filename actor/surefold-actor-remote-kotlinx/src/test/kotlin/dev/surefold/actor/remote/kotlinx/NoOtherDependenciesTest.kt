package dev.surefold.actor.remote.kotlinx

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-actor-remote-kotlinx may put on a service's classpath: surefold-actor-remote's own, and kotlinx's
 * ProtoBuf.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "kotlinx-serialization-")

    @Test
    fun `the main runtime classpath is surefold-actor-remote and kotlinx serialization, and nothing else`() {
        val raw = System.getProperty("surefold.actor.remote.kotlinx.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.remote.kotlinx.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-remote-kotlinx must stay surefold-actor-remote and kotlinx, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
