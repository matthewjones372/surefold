package io.github.matthewjones372.surefold.actor.pekko

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module puts on a consumer's classpath: surefold-pekko and pekko-actor-typed, with what they bring. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "pekko-actor",
        // What pekko-actor brings: the Scala runtime it is written in, and Typesafe Config.
        "scala-library",
        "config-",
        // What pekko-actor-typed brings: typed Pekko logs through slf4j, so the facade and Pekko's bridge to it
        // arrive with the artifact and are not declared here.
        "pekko-slf4j",
        "slf4j-api",
    )

    @Test
    fun `the main runtime classpath is surefold-pekko and pekko-actor-typed, and nothing else`() {
        val raw = System.getProperty("surefold.actor.pekko.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.pekko.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-pekko must stay surefold-pekko plus pekko-actor-typed, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
