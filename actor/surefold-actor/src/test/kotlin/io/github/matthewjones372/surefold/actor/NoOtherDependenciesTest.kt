package io.github.matthewjones372.surefold.actor

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What surefold-actor may put on a consumer's classpath: the Kotlin standard library and surefold. No actor system. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        // surefold itself, and only surefold: every other surefold module starts with the same word.
        Regex("""surefold(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is surefold, and nothing else`() {
        val raw = System.getProperty("surefold.actor.runtimeClasspath")
        withClue("the build must pass -Dsurefold.actor.runtimeClasspath; see surefold-actor/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-actor must stay surefold, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
