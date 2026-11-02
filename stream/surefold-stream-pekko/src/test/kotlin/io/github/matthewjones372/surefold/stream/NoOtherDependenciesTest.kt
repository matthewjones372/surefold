package io.github.matthewjones372.surefold.stream

import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldNotBeNull
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * What surefold-stream-pekko is allowed to put on a consumer's classpath, stated
 * as a test: surefold-stream and everything it brings, surefold-pekko, Pekko Streams
 * with the Scala runtime and the libraries Pekko itself needs. No HTTP
 * library, no JSON library, no coroutines, no second functional stack.
 */
class NoOtherDependenciesTest {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        // The description this module runs, the surefold it is written in, and the
        // surefold-pekko whose `await` it waits through.
        "surefold",
        "surefold-stream",
        "surefold-pekko",
        "pekko-",
        // What pekko-stream brings with it: the Scala runtime it is written
        // in, Typesafe Config, the Reactive Streams interfaces its operators
        // implement, and the TLS settings parser pekko-stream's TLS stage
        // reads. Every one of these arrives through pekko-stream and none is
        // declared here.
        "scala-library",
        "config-",
        "reactive-streams-",
        "ssl-config-core_",
    )

    @Test
    fun `the main runtime classpath is surefold-stream, surefold-pekko and pekko-stream, and nothing else`() {
        val raw = System.getProperty("surefold.stream.runtimeClasspath")
        withClue("the build must pass -Dsurefold.stream.runtimeClasspath; see surefold-stream-pekko/build.gradle.kts") {
            raw.shouldNotBeNull()
        }

        val unexpected = raw!!.split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .filterNot { entry -> allowed.any { entry.startsWith(it) } }

        withClue(
            "surefold-stream-pekko must stay surefold-stream, pekko-stream and surefold-pekko, but found: $unexpected",
        ) {
            unexpected.shouldBeEmpty()
        }
    }
}
