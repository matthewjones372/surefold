package dev.surefold.arrow

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold, and Arrow's core. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "arrow-", "surefold")

    @Test
    fun `the main runtime classpath is surefold and arrow-core, and nothing else`() {
        val raw = System.getProperty("surefold.arrow.runtimeClasspath")
        withClue("the build must pass -Dsurefold.arrow.runtimeClasspath; see core/surefold-arrow/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-arrow must stay surefold plus arrow-core, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
