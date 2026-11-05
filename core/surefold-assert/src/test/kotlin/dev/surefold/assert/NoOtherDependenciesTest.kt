package dev.surefold.assert

import java.io.File
import org.junit.jupiter.api.Test

/** What this module may put on a consumer's classpath, stated as a test: surefold and opentest4j, and nothing else. */
class NoOtherDependenciesTest {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "opentest4j")

    @Test
    fun `the main runtime classpath is surefold and opentest4j, and nothing else`() {
        val raw =
            System.getProperty("surefold.assert.runtimeClasspath")
                ?: error(
                    "the build must pass -Dsurefold.assert.runtimeClasspath; see core/surefold-assert/build.gradle.kts",
                )

        raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } } shouldBe emptyList()
    }
}
