package io.github.matthewjones372.surefold.openfeature

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold, and the SDK alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "sdk-",
        // The SDK logs through it and fails without it (spec 0180, open question 1).
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is surefold and the OpenFeature SDK, and nothing else`() {
        val raw = System.getProperty("surefold.openfeature.runtimeClasspath")
        withClue(
            "the build must pass -Dsurefold.openfeature.runtimeClasspath; see surefold-openfeature/build.gradle.kts",
        ) {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-openfeature must stay surefold plus the OpenFeature SDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
