package dev.surefold.cluster.saga

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    @Test
    fun `the main runtime classpath is surefold-actor and what it brings, and nothing else`() {
        val raw = System.getProperty("surefold.cluster.saga.runtimeClasspath")
        withClue("the build must pass -Dsurefold.cluster.saga.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-cluster-saga must stay surefold-actor and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
