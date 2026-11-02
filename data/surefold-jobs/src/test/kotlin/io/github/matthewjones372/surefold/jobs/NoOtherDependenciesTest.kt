package io.github.matthewjones372.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-jobs may put on a service's classpath: surefold's own, and nothing more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold, and nothing else") {
            val raw = System.getProperty("surefold.jobs.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.jobs.runtimeClasspath; see its build.gradle.kts" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
