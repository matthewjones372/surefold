package dev.surefold.stream

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-stream-test is allowed to put on a consumer's classpath, stated as a test: surefold-stream-forks
 * and what it brings, which is surefold-stream, surefold and the Kotlin standard library.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        Regex("""kotlin-stdlib.*"""),
        Regex("""annotations-.*"""),
        Regex("""surefold(-\d.*)?\.jar"""),
        Regex("""surefold-stream(-\d.*)?\.jar"""),
        Regex("""surefold-stream-forks(-\d.*)?\.jar"""),
    )

    @Test
    fun `the main runtime classpath is surefold-stream-forks and what it brings, and nothing else`() {
        val raw = System.getProperty("surefold.stream.runtimeClasspath")
        withClue("the build must pass -Dsurefold.stream.runtimeClasspath; see surefold-stream-test/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-stream-test must stay surefold-stream-forks and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
