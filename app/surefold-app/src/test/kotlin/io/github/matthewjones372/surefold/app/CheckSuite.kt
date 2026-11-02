package io.github.matthewjones372.surefold.app

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.withClue
import dev.surefold.junit.withTempDirectory
import java.io.File

/**
 * Run against this module's own test output, so discovery is the real thing: `Sampled` and `Faulty`
 * in `SurefoldAppSuite.kt` are the applications it finds.
 */
object CheckSuite : Suite() {
    // The clue is for a build that forgot the property, not for what the test then checks.
    private fun testClasses(then: (List<File>) -> Unit) =
        System.getProperty("surefold.app.testClasses")
            ?.let { dirs -> then(dirs.split(File.pathSeparator).filter { it.isNotBlank() }.map(::File)) }
            ?: withClue("the build must pass -Dsurefold.app.testClasses; see surefold-app/build.gradle.kts") {
                null.shouldNotBeNull()
            }

    init {
        test("an application declared as an object is found by its supertype") {
            testClasses { classes ->
                val found = apps(classes).map { it::class.simpleName }

                found shouldContain "Sampled"
                found shouldContain "Faulty"
            }
        }

        test("a graph short of a key fails the check and names the application") {
            testClasses { classes ->
                val checked = checkWiring(classes)

                checked.failed shouldBe true
                checked.report shouldContain "Faulty"
                checked.report shouldContain "missing File"
            }
        }

        test("a diagram is written for every application found") {
            testClasses { classes ->
                withTempDirectory { dir ->
                    val into = dir.toFile()
                    val _ = checkWiring(classes, diagrams = into)

                    File(into, "Sampled.mmd").readText() shouldContain "graph TD"
                }
            }
        }

        test("a directory with no application passes and says nothing") {
            withTempDirectory { dir ->
                val checked = checkWiring(listOf(dir.toFile()))

                checked.failed shouldBe false
                checked.report shouldBe ""
            }
        }

        test("a warning stops the build only where the caller asked it to") {
            testClasses { classes ->
                withTempDirectory { dir ->
                    val only = dir.toFile()
                    alone(classes, "Warned", only)

                    withClue("Warned provides one key twice, which the default floor lets through") {
                        checkWiring(listOf(only)).failed shouldBe false
                    }
                    checkWiring(listOf(only), failOn = Severity.WARN).failed shouldBe true
                }
            }
        }
    }

    /**
     * One class file copied into [into], so the check sees one application. Only the scan reads this
     * directory; loading goes through the classloader that already holds the test classpath.
     */
    private fun alone(testClasses: List<File>, name: String, into: File) {
        val path = "io/github/matthewjones372/surefold/app/$name.class"
        val source = testClasses.map { File(it, path) }.first { it.exists() }
        val target = File(into, path)
        target.parentFile.mkdirs()
        val _ = source.copyTo(target)
    }
}
