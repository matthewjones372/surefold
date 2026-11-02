package dev.surefold.app.typesafe

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "config-")

    @Test
    fun `the main runtime classpath is surefold-app and Typesafe Config, and nothing else`() {
        val raw = System.getProperty("surefold.app.typesafe.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.typesafe.runtimeClasspath; see the build file") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-typesafe must stay surefold-app plus config, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
