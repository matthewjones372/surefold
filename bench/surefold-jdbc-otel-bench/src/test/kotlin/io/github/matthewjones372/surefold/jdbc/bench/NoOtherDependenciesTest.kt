package io.github.matthewjones372.surefold.jdbc.bench

import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import java.io.File

object NoOtherDependenciesTest : Suite() {
    init {
        test("the benchmark runtime is JDBC tracing with its SDK driver and pool") {
            val raw = System.getProperty("surefold.jdbc.otel.bench.runtimeClasspath")
            expect(raw != null) { "the build must supply the main runtime classpath" }
            val allowed = setOf(
                "surefold", "surefold-otel", "surefold-jdbc-otel", "kotlin-stdlib", "annotations",
                "opentelemetry-api", "opentelemetry-api-incubator", "opentelemetry-context", "opentelemetry-common",
                "opentelemetry-sdk", "opentelemetry-sdk-common", "opentelemetry-sdk-trace",
                "opentelemetry-sdk-metrics", "opentelemetry-sdk-logs", "opentelemetry-jdbc",
                "opentelemetry-instrumentation-api", "opentelemetry-instrumentation-api-incubator",
                "opentelemetry-semconv",
                "postgresql", "checker-qual", "HikariCP", "slf4j-api",
            )
            raw!!.split(File.pathSeparator)
                .map { it.replace(Regex("-\\d.*\\.jar$"), "") }
                .filter { it !in allowed } shouldBe emptyList()
        }
    }
}
