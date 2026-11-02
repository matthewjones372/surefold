package io.github.matthewjones372.surefold.pekko

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module is allowed to put on a consumer's classpath, stated as a
 * test: surefold, and pekko-actor with what it brings. No HTTP library,
 * no JSON library, no coroutines.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "pekko-actor",
        // What pekko-actor brings with it: the Scala runtime it is written in
        // and Typesafe Config, which is what a dispatcher is configured in.
        // Both arrive through pekko-actor and neither is declared here.
        "scala-library",
        "config-",
    )

    @Test
    fun `the main runtime classpath is surefold and pekko-actor, and nothing else`() {
        val raw = System.getProperty("surefold.pekko.runtimeClasspath")
        withClue("the build must pass -Dsurefold.pekko.runtimeClasspath; see surefold-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-pekko must stay surefold plus pekko-actor, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
