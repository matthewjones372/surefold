package io.github.matthewjones372.surefold.otel

import io.github.matthewjones372.surefold.assert.shouldBeEmpty
import io.github.matthewjones372.surefold.assert.shouldNotBeNull
import io.github.matthewjones372.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: surefold, and the API alone. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // The API, context and shared types. No SDK: a service brings its own,
        // with the exporter and the agent it has chosen.
        "opentelemetry-api",
        "opentelemetry-context",
        "opentelemetry-common-",
    )

    @Test
    fun `the main runtime classpath is surefold and the OpenTelemetry API, and nothing else`() {
        val raw = System.getProperty("surefold.otel.runtimeClasspath")
        withClue("the build must pass -Dsurefold.otel.runtimeClasspath; see surefold-otel/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-otel must stay surefold plus the OpenTelemetry API, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
