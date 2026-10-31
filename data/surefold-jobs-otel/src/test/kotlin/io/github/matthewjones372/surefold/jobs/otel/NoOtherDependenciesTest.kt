package io.github.matthewjones372.surefold.jobs.otel

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-jobs-otel may put on a service's classpath: surefold-jobs', surefold-otel's, and nothing more. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        "opentelemetry-api",
        "opentelemetry-context",
        "opentelemetry-common-",
    )

    init {
        test("the main runtime classpath is surefold-jobs and surefold-otel, and nothing else") {
            val raw = System.getProperty("surefold.jobs.otel.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.jobs.otel.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
