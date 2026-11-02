package dev.surefold.ratelimit

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-ratelimit may put on a service's classpath: surefold's own, and nothing more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold, and nothing else") {
            val raw = System.getProperty("surefold.ratelimit.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.ratelimit.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
