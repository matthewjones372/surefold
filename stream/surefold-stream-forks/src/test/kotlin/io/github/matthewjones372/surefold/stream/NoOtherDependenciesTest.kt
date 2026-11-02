package io.github.matthewjones372.surefold.stream

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-stream-forks is allowed to put on a consumer's classpath, stated as a test: surefold-stream and
 * what it brings, which is surefold and the Kotlin standard library. A run on forks needs nothing else.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""surefold(-\d.*)?\.jar"""),
        Regex("""surefold-stream(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is surefold-stream and surefold, and nothing else`() {
        val raw = System.getProperty("surefold.stream.runtimeClasspath")
        withClue("the build must pass -Dsurefold.stream.runtimeClasspath; see surefold-stream-forks/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-stream-forks must stay surefold-stream and surefold, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
