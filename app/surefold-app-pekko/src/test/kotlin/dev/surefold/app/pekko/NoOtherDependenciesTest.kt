package dev.surefold.app.pekko

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
        "pekko-actor",
        "scala-library",
        "config-",
        // What pekko-actor-typed brings: typed Pekko logs through slf4j rather than the classic event
        // bus, so the facade and Pekko's bridge to it arrive with the artifact and are not declared here.
        "pekko-slf4j",
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is surefold-app and pekko-actor-typed, and nothing else`() {
        val raw = System.getProperty("surefold.app.pekko.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.pekko.runtimeClasspath; see surefold-app-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-pekko must stay surefold-app plus pekko-actor-typed, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
