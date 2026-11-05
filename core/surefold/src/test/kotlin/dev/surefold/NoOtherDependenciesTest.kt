package dev.surefold

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import java.io.File
import org.junit.jupiter.api.Test

/**
 * What this module is allowed to put on a consumer's classpath, stated as a test — the claim `AGENTS.md` makes for it:
 * the JDK and the Kotlin standard library, and nothing else. No JSON library, no HTTP library, no coroutines.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-")

    @Test
    fun `the main runtime classpath is the standard library, and nothing else`() {
        val raw = System.getProperty("surefold.runtimeClasspath")
        withClue("the build must pass -Dsurefold.runtimeClasspath; see build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected =
            raw!!
                .split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold must stay the JDK and the standard library, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
