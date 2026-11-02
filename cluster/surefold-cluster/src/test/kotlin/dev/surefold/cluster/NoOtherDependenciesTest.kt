package dev.surefold.cluster

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    @Test
    fun `the main runtime classpath is surefold-actor-remote and what it brings, and nothing else`() {
        val raw = System.getProperty("surefold.cluster.runtimeClasspath")
        withClue("the build must pass -Dsurefold.cluster.runtimeClasspath; see surefold-cluster/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-cluster must stay surefold-actor-remote and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
