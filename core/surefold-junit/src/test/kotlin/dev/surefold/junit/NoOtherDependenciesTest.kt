package dev.surefold.junit

import dev.surefold.assert.shouldBe
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What this module may put on a consumer's classpath: surefold, surefold-assert, surefold-test and the JUnit engine
 * API.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "junit-platform-engine",
        "junit-platform-commons",
        "opentest4j",
    )

    private val surefold = Regex("""surefold(-assert|-test)?(-\d.*)?\.jar""")

    @Test
    fun `the main runtime classpath is surefold, surefold-assert, surefold-test and junit-platform-engine, no more`() {
        val raw = System.getProperty("surefold.junit.runtimeClasspath")
            ?: error("the build must pass -Dsurefold.junit.runtimeClasspath; see core/surefold-junit/build.gradle.kts")

        raw.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> surefold.matches(entry) || allowed.any { entry.startsWith(it) } } shouldBe emptyList()
    }
}
