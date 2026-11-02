package io.github.matthewjones372.surefold.app.cluster

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "config-")

    @Test
    fun `the main runtime classpath is surefold-app-actor, surefold-app-typesafe and surefold-cluster, no backend`() {
        val raw = System.getProperty("surefold.app.cluster.runtimeClasspath")
        withClue(
            "the build must pass -Dsurefold.app.cluster.runtimeClasspath; see surefold-app-cluster/build.gradle.kts",
        ) {
            raw.shouldNotBeNull()
        }

        val unexpected = raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-cluster must bring no discovery backend of its own, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
