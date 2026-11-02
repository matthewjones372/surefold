package dev.surefold.auth

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-auth may put on a service's classpath: surefold's, and Bouncy Castle's provider for Argon2id. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf("kotlin-stdlib", "annotations-", "surefold", "bcprov-jdk18on-")

    init {
        test("the main runtime classpath is surefold and Bouncy Castle's provider, and nothing else") {
            val raw = System.getProperty("surefold.auth.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.auth.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
