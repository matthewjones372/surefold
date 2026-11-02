package io.github.matthewjones372.surefold.actor.journal.jdbc

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-actor-journal-jdbc may put on a service's classpath: surefold-actor's own, and nothing more. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
    )

    @Test
    fun `the main runtime classpath is surefold-actor, and nothing else`() {
        val raw = System.getProperty("surefold.actor.journal.jdbc.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.journal.jdbc.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-actor-journal-jdbc must stay surefold-actor and the JDK, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
