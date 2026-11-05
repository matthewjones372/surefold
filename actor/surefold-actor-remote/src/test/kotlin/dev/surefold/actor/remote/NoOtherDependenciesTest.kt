package dev.surefold.actor.remote

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import java.io.File
import org.junit.jupiter.api.Test

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    @Test
    fun `the main runtime classpath is surefold-actor and what it brings, and nothing else`() {
        val raw = System.getProperty("surefold.actor.remote.runtimeClasspath")
        withClue(
            "the build must pass -Dsurefold.actor.remote.runtimeClasspath; see surefold-actor-remote/build.gradle.kts",
        ) {
            raw.shouldNotBeNull()
        }

        val unexpected =
            raw.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-remote must stay surefold-actor and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
