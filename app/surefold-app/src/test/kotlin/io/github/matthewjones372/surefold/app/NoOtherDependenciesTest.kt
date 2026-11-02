package io.github.matthewjones372.surefold.app

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
    )

    @Test
    fun `the main runtime classpath is surefold, and nothing else`() {
        val raw = System.getProperty("surefold.app.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.runtimeClasspath; see surefold-app/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app must stay surefold alone, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
