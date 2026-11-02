package io.github.matthewjones372.surefold.slf4j

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold, and the facade alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The facade. No backend: a service brings the one it has already configured, and two on a
        // classpath is a warning at start-up and an arbitrary winner.
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is surefold and the SLF4J facade, and nothing else`() {
        val raw = System.getProperty("surefold.slf4j.runtimeClasspath")
        withClue("the build must pass -Dsurefold.slf4j.runtimeClasspath; see surefold-slf4j/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-slf4j must stay surefold plus the SLF4J facade, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
