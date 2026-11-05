package dev.surefold.app.gradle

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.shouldNotContain
import dev.surefold.assert.withClue
import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Spec 0041: a build that applies `dev.surefold.compiler` alone, with no wiring plugin, and compiles against this
 * repository's own surefold, resolving the checker from the local repository as a consumer does.
 */
class SurefoldForksPluginTest {

    private fun project(dir: File, source: String) {
        val underTest = System.getProperty("surefold.app.underTest")
        val repo = System.getProperty("surefold.checker.repo")
        val version = System.getProperty("surefold.checker.version")
        withClue("the build must pass -Dsurefold.app.underTest and -Dsurefold.checker.repo") {
            underTest.shouldNotBeNull()
            repo.shouldNotBeNull()
        }
        val jars = underTest!!.split(File.pathSeparator).joinToString(", ") { "\"$it\"" }
        File(dir, "settings.gradle.kts")
            .writeText(
                """
            pluginManagement {
                repositories {
                    maven { url = uri("$repo") }
                    gradlePluginPortal()
                }
            }

            rootProject.name = "under-test"
            """
                    .trimIndent(),
            )
        File(dir, "build.gradle.kts")
            .writeText(
                """
            plugins {
                kotlin("jvm") version "2.4.10"
                id("dev.surefold.compiler") version "$version"
            }

            repositories {
                maven { url = uri("$repo") }
                mavenCentral()
            }

            dependencies { implementation(files($jars)) }
            """
                    .trimIndent(),
            )
        File(dir, "src/main/kotlin/Under.kt").apply { parentFile.mkdirs() }.writeText(source)
    }

    private fun runner(dir: File) = GradleRunner.create().withProjectDir(dir).withArguments("classes", "--stacktrace")

    @Test
    fun `a Deferred returned from a scope fails the compile`(@TempDir dir: File) {
        project(dir, ESCAPING)

        val result = runner(dir).buildAndFail()

        result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.FAILED
        result.output shouldContain "surefold: scope returns a Deferred"
    }

    @Test
    fun `the value a scope awaited compiles`(@TempDir dir: File) {
        project(dir, AWAITED)

        val result = runner(dir).build()

        result.task(":compileKotlin")?.outcome shouldBe TaskOutcome.SUCCESS
        withClue("a checker built for another compiler warns rather than going quiet") {
            result.output shouldNotContain "surefold-compiler was built for Kotlin"
        }
    }
}

private const val ESCAPING =
    """
import dev.surefold.scope

fun load() = scope { async { 1 } }
"""

private const val AWAITED =
    """
import dev.surefold.scope

fun load(): Int = scope { async { 1 }.await() }
"""
