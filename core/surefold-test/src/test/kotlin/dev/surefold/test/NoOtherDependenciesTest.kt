package dev.surefold.test

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import java.io.File
import org.junit.jupiter.api.Test

/** What surefold-test may put on a consumer's classpath: surefold and what it brings, and no assertion library. */
class NoOtherDependenciesTest {

    private val allowed =
        listOf(
            Regex("""kotlin-stdlib.*"""),
            Regex("""annotations-.*"""),
            Regex("""surefold(-\d.*)?\.jar"""),
        )

    @Test
    fun `the main runtime classpath is surefold and what it brings, and nothing else`() {
        val raw = System.getProperty("surefold.test.runtimeClasspath")
        withClue("the build must pass -Dsurefold.test.runtimeClasspath; see surefold-test/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected =
            raw!!
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { it.matches(entry) } }

        withClue("surefold-test must stay surefold and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
