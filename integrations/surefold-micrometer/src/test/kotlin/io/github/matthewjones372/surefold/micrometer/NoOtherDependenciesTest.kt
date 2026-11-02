package io.github.matthewjones372.surefold.micrometer

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold, and the registry. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The registry interface, and what micrometer-core itself brings.
        "micrometer-core",
        "micrometer-commons",
        "micrometer-observation",
        "HdrHistogram",
        "LatencyUtils",
    )

    @Test
    fun `the main runtime classpath is surefold and Micrometer, and nothing else`() {
        val raw = System.getProperty("surefold.micrometer.runtimeClasspath")
        withClue(
            "the build must pass -Dsurefold.micrometer.runtimeClasspath; see surefold-micrometer/build.gradle.kts",
        ) {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-micrometer must stay surefold plus Micrometer, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
