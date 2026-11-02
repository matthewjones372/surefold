package io.github.matthewjones372.surefold.openfeature.postgres

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-openfeature-postgres may put on a service's classpath: surefold's own and the OpenFeature SDK's. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "sdk-", "slf4j-api")

    init {
        test("the main runtime classpath is surefold and the OpenFeature SDK, and nothing else") {
            val raw = System.getProperty("surefold.openfeature.postgres.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.openfeature.postgres.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
