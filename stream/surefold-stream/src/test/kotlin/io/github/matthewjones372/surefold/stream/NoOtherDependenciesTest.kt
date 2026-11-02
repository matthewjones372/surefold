package io.github.matthewjones372.surefold.stream

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-stream is allowed to put on a consumer's classpath, stated as a test: the Kotlin standard
 * library and surefold. No Pekko, no surefold-pekko: a description names no
 * backend, and a service picks one by depending on it.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        // surefold itself, and only surefold: `surefold-pekko` and every other surefold module start with the same
        // word.
        Regex("""surefold(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is surefold, and nothing else`() {
        val raw = System.getProperty("surefold.stream.runtimeClasspath")
        withClue("the build must pass -Dsurefold.stream.runtimeClasspath; see surefold-stream/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-stream must stay surefold, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
