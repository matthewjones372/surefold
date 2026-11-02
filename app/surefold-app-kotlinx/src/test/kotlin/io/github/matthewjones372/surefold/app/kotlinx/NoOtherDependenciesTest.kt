package io.github.matthewjones372.surefold.app.kotlinx

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed =
        listOf("kotlin-stdlib", "annotations-", "surefold", "config-", "kotlinx-serialization-")

    @Test
    fun `the main runtime classpath is surefold-app-typesafe and kotlinx serialization, and nothing else`() {
        val raw = System.getProperty("surefold.app.kotlinx.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.kotlinx.runtimeClasspath; see the build file") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue(
            "surefold-app-kotlinx must stay surefold-app-typesafe plus kotlinx serialization, but found: $unexpected",
        ) {
            unexpected.shouldBeEmpty()
        }
    }
}
