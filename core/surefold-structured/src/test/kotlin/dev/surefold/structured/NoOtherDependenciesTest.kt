package dev.surefold.structured

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** surefold and the JDK: the module's whole point is that the JDK now has what it needs. */
class NoOtherDependenciesTest {

    private val allowed = listOf("surefold", "kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is surefold, and nothing else`() {
        val raw = System.getProperty("surefold.structured.runtimeClasspath")
        withClue("the build must pass -Dsurefold.structured.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-structured must stay surefold and the JDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
