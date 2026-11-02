package dev.surefold.app.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-app-jobs may put on a service's classpath: surefold-app's and surefold-jobs', no migration tool. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
    )

    init {
        test("the main runtime classpath is surefold-app and surefold-jobs, and no Liquibase") {
            val raw = System.getProperty("surefold.app.jobs.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.app.jobs.runtimeClasspath; see its build.gradle.kts" }
            val entries = raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
            entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }.shouldBeEmpty()
            // "surefold" admits surefold-app-liquibase too, which would bring Liquibase with it.
            entries.filter { "liquibase" in it }.shouldBeEmpty()
        }
    }
}
