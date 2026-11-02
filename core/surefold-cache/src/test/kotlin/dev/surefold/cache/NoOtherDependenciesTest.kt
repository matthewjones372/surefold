package dev.surefold.cache

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-cache may put on a service's classpath: surefold's own, and nothing more. */
class NoOtherDependenciesTest {

    private val allowed = listOf("surefold", "kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is surefold, and nothing else`() {
        val raw = System.getProperty("surefold.cache.runtimeClasspath")
        withClue("the build must pass -Dsurefold.cache.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold.cache must stay surefold alone, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
