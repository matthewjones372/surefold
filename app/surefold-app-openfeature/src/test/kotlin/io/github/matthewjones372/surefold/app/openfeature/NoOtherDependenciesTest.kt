package io.github.matthewjones372.surefold.app.openfeature

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module may put on a consumer's classpath, stated as a test: surefold-app, surefold-openfeature and the SDK.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "sdk-", "slf4j-api")

    @Test
    fun `the main runtime classpath is surefold-app, surefold-openfeature and the SDK, and nothing else`() {
        val raw = System.getProperty("surefold.app.openfeature.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.openfeature.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-openfeature must stay surefold-app plus surefold-openfeature, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
