package io.github.matthewjones372.surefold.idempotency

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-idempotency may put on a service's classpath. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold")

    init {
        test("the main runtime classpath is surefold and surefold-jobs, and nothing else") {
            val raw = System.getProperty("surefold.idempotency.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.idempotency.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
