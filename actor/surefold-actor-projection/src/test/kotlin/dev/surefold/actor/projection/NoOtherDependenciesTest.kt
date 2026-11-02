package dev.surefold.actor.projection

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-actor-projection may put on a service's classpath: surefold-actor's and surefold-stream's own, and no
 * more.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
    )

    @Test
    fun `the main runtime classpath is surefold-actor and surefold-stream, and nothing else`() {
        val raw = System.getProperty("surefold.actor.projection.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.projection.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-projection must stay surefold-actor and surefold-stream, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
