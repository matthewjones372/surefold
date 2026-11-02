package io.github.matthewjones372.surefold.stream.actors

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-stream-actors is allowed to put on a consumer's classpath, stated as a test: surefold-stream-forks and
 * surefold-actor, and what they bring, which is surefold-stream, surefold and the Kotlin standard library.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""surefold(-\d.*)?\.jar"""),
        Regex("""surefold-stream(-\d.*)?\.jar"""),
        Regex("""surefold-stream-forks(-\d.*)?\.jar"""),
        Regex("""surefold-actor(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is surefold-stream-forks, surefold-actor, what they bring, and nothing else`() {
        val raw = System.getProperty("surefold.stream.actors.runtimeClasspath")
        withClue(
            "the build must pass -Dsurefold.stream.actors.runtimeClasspath; " +
                "see surefold-stream-actors/build.gradle.kts",
        ) {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-stream-actors must stay surefold-stream-forks and surefold-actor, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
