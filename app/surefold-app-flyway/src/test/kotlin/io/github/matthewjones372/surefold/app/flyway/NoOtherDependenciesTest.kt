package io.github.matthewjones372.surefold.app.flyway

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-app-flyway may put on a service's classpath: surefold-app's and Flyway's, and no Liquibase. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // Flyway Community and what it brings. No driver and no DataSource: both are the application's.
        "flyway-",
        // flyway-core's annotations. Not jackson-databind, which it reads TOML and JSON configuration with.
        "jackson-annotations",
    )

    init {
        test("the main runtime classpath is surefold-app and Flyway, and nothing else") {
            val raw = System.getProperty("surefold.app.flyway.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.app.flyway.runtimeClasspath; see build.gradle.kts" }
            val entries = raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
            entries.filterNot { entry -> allowed.any { entry.startsWith(it) } }.shouldBeEmpty()
            entries.filter { "liquibase" in it }.shouldBeEmpty()
        }
    }
}
