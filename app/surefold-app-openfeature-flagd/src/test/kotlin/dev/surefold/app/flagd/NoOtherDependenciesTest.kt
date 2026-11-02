package dev.surefold.app.flagd

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/** What this module may put on a consumer's classpath, stated as a test: flagd and what it brings, and no more. */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib", "annotations-", "surefold", "sdk-", "slf4j-api",
        // Through surefold-app-typesafe (spec 0184, open question 3).
        "config-",
        // flagd and its in-process evaluator: JsonLogic, the schema validator and the JSON they read.
        "flagd-", "json-logic-java", "json-schema-validator", "itu-", "jackson-", "snakeyaml", "gson",
        "commons-codec", "commons-collections4", "commons-lang3", "semver4j",
        // flagd's gRPC client, built even when only the in-process resolver runs: the cost the spec names.
        "grpc-", "protobuf-java", "proto-google-common-protos", "perfmark-api", "guava", "failureaccess",
        "listenablefuture", "error_prone_annotations", "j2objc-annotations", "jsr305", "jspecify",
        "animal-sniffer-annotations", "opentelemetry-",
    )

    @Test
    fun `the main runtime classpath is surefold-app-openfeature, Typesafe Config and flagd, and nothing else`() {
        val raw = System.getProperty("surefold.app.flagd.runtimeClasspath")
        withClue("the build must pass -Dsurefold.app.flagd.runtimeClasspath; see its build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue("surefold-app-openfeature-flagd must stay flagd and what it brings, but found: $unexpected") {
            unexpected.shouldBeEmpty()
        }
    }
}
