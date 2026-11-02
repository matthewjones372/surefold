package io.github.matthewjones372.surefold.app.pekko

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.withClue
import org.jetbrains.kotlin.cli.common.ExitCode
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader

class CacheCallerCompatibilityTest {
    @TempDir
    lateinit var workspace: File

    @Test
    fun `the existing cache caller still compiles and runs`() {
        val source = fixture("CacheCaller016.kt").readText()
        val (exit, errors) = EmbeddedKotlin(workspace).compile(source)
        withClue(errors.joinToString("\n")) {
            errors.shouldBeEmpty()
            exit shouldBe ExitCode.OK
        }
        runFrom(File(workspace, "out").toURI().toURL()) shouldBe true
    }

    private fun fixture(path: String): URL =
        javaClass.getResource("/cache-caller-016/$path") ?: error("Missing old-consumer fixture: $path")

    private fun runFrom(output: URL): Boolean =
        URLClassLoader(arrayOf(output), javaClass.classLoader).use { loader ->
            try {
                loader.loadClass("compatibility.cache016.CacheCaller016")
                    .getMethod("run").invoke(null) as Boolean
            } catch (failed: InvocationTargetException) {
                throw failed.cause ?: failed
            }
        }
}
