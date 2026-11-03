package dev.surefold.sqs

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-sqs-test may put on a test classpath: surefold-sqs, ElasticMQ, Jupiter's API, and what they bring. */
object NoOtherDependenciesTest : Suite() {

    private fun jars(property: String): List<String> {
        val raw = System.getProperty(property)
        expect(raw != null) { "the build must pass -D$property; see surefold-sqs-test/build.gradle.kts" }
        return raw!!.split(File.pathSeparator).filter { it.isNotBlank() }
    }

    init {
        test("the main runtime classpath is surefold-sqs, ElasticMQ and Jupiter's API, and nothing beside them") {
            val allowed = jars("surefold.sqs.test.allowedClasspath").toSet()
            jars("surefold.sqs.test.runtimeClasspath").filterNot { it in allowed }.shouldBeEmpty()
        }
    }
}
