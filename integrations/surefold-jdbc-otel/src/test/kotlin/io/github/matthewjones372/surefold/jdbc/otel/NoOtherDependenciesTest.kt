package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import java.io.File

object NoOtherDependenciesTest : Suite() {
    init {
        test("the JDBC privacy integration adds no SDK driver or pool to the API runtime") {
            val raw = System.getProperty("surefold.jdbc.otel.runtimeClasspath")
            expect(raw != null) { "the build must supply the main runtime classpath" }
            val allowed = listOf(
                "surefold", "kotlin-stdlib", "annotations-",
                "opentelemetry-api-", "opentelemetry-context-", "opentelemetry-common-",
            )
            raw!!.split(File.pathSeparator)
                .filter { entry -> allowed.none { entry.startsWith(it) } } shouldBe emptyList()
        }
    }
}
