package dev.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import java.io.File

private class Widget

private class Cog

/**
 * The line numbers below are load-bearing: `single` is inline, so the line the JVM reports for the frame is a synthetic
 * one past the end of this file, and the whole point of reading the SMAP is that a site names a line somebody can open.
 * Adding a line above one of these breaks the test, which is the test working.
 */
object CallSiteLineSuite : Suite() {
    private const val DECLARED_ON_LINE = 21

    private val module = single { _: Widget -> Cog() }

    init {
        test("a site names the line the recipe was written on, not the line the JVM reports") {
            module.nodes.values.single().site.shouldNotBeNull { site ->
                site shouldBe "dev/surefold/app/CallSiteLineSuite.kt:$DECLARED_ON_LINE"
            }
        }

        test("a line past the end of the file would be one nobody could open") {
            module.nodes.values.single().site.shouldNotBeNull { site ->
                val line = site.substringAfterLast(':').toInt()

                withClue("this file is shorter than the synthetic lines the inliner emits") {
                    (line <= lines()) shouldBe true
                }
            }
        }
    }

    // The repository root, which the build already hands every test in this module.
    private fun lines(): Int =
        File(System.getProperty("surefold.app.repoRoot"), "app/surefold-app/src/test/kotlin/$SOURCE").readLines().size
}

private const val SOURCE = "dev/surefold/app/CallSiteLineSuite.kt"
