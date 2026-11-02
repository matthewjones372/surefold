package io.github.matthewjones372.surefold.auth.oidc

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-auth-oidc may put on a service's classpath: surefold-auth's, and Nimbus's SDK with what it needs. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "surefold", "bcprov-jdk18on-",
        "oauth2-oidc-sdk-", "nimbus-jose-jwt-", "content-type-", "lang-tag-", "json-smart-", "accessors-smart-",
        "asm-", "jcip-annotations-",
    )

    init {
        test("the main runtime classpath is surefold-auth and Nimbus's SDK, and nothing else") {
            val raw = System.getProperty("surefold.auth.oidc.runtimeClasspath")
            expect(raw != null) { "the build must pass -Dsurefold.auth.oidc.runtimeClasspath; see its build file" }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
