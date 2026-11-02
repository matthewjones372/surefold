package dev.surefold.cache.redis

import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBeEmpty
import java.io.File

/** What surefold-cache-redis may put on a service's classpath: surefold's own, and the Lettuce client. */
object NoOtherDependenciesTest : Suite() {

    private val allowed = listOf(
        "kotlin-stdlib",
        "annotations-",
        "surefold",
        // Lettuce, and the Netty and Reactor it runs on.
        "lettuce-core-", "netty-", "reactor-core-", "reactive-streams-",
    )

    init {
        test("the main runtime classpath is surefold and the Lettuce client, and nothing else") {
            val raw = System.getProperty("surefold.cache.redis.runtimeClasspath")
            expect(raw != null) {
                "the build must pass -Dsurefold.cache.redis.runtimeClasspath; see its build file"
            }
            raw!!.split(File.pathSeparator)
                .filter { it.isNotBlank() }
                .filterNot { entry -> allowed.any { entry.startsWith(it) } }
                .shouldBeEmpty()
        }
    }
}
