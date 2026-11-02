package dev.surefold.tenancy.test

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-tenancy-test may put on a service's test classpath: surefold-tenancy's, and nothing more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold-tenancy, and nothing else") {
            val raw = System.getProperty("surefold.tenancy.test.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.tenancy.test.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
