package dev.surefold.sns

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-sns-test may put on a test classpath: surefold-sns, surefold-sqs-test, and what they bring. */
object NoOtherDependenciesTest : Suite() {

    private fun jars(property: String): List<String> {
        val raw = System.getProperty(property)
        expect(raw != null) { "the build must pass -D$property; see surefold-sns-test/build.gradle.kts" }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    init {
        test("the main runtime classpath is surefold-sns and surefold-sqs-test, and nothing beside them") {
            val allowed = jars("surefold.sns.test.allowedClasspath").toSet()
            jars("surefold.sns.test.runtimeClasspath").filterNot { it in allowed }.shouldBeEmpty()
        }
    }
}
