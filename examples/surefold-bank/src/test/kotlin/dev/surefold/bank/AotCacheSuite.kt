package dev.surefold.bank

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldNotBeNull
import dev.surefold.junit.timeout
import dev.surefold.junit.withClue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** A JVM on the installed jars running the bank with [jvmArgs]: its exit code and what it printed. */
private fun bankJvm(vararg jvmArgs: String): Pair<Int, String> {
    val classpath = System.getProperty("surefold.bank.aotClasspath")
    withClue("the build must pass -Dsurefold.bank.aotClasspath; see surefold-bank/build.gradle.kts") {
        classpath.shouldNotBeNull()
    }
    val java = ProcessHandle.current().info().command().orElseThrow()
    val command = listOf(java, *jvmArgs, "-cp", classpath, "dev.surefold.bank.MainKt", "--train")
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val output = process.inputStream.bufferedReader().readText()
    check(process.waitFor(2, TimeUnit.MINUTES)) { "the bank's JVM never left: $output" }
    return process.exitValue() to output
}

object AotCacheSuite : Suite() {
    init {
        test("a training run starts the bank, moves money through it, and returns", timeout(3.minutes)) {
            val lines = capturingLogs { logs ->
                main(arrayOf("--train"))
                logs.all().map { it.message }
            }

            lines.any { it.startsWith("started in ") } shouldBe true
            lines.any { it.startsWith("ready in ") } shouldBe true
            lines.last() shouldBe "trained on $TRAINING_TRANSFERS transfers"
        }

        test(
            "a JVM trained on the installed jars leaves a cache the next one starts on, or refuses to start",
            timeout(5.minutes),
        ) {
            val cache = File(Files.createTempDirectory("bank-aot").toFile(), "bank.aot")

            val (trained, training) = bankJvm("-XX:AOTCacheOutput=$cache")
            withClue("the training run must leave of its own accord, with nothing keeping its JVM alive: $training") {
                trained shouldBe 0
            }
            cache.exists() shouldBe true

            // AOTMode=on: a cache this JVM cannot use is an error at start, rather than a quietly cold start.
            val (started, output) = bankJvm("-XX:AOTCache=$cache", "-XX:AOTMode=on")
            withClue("a JVM on the same jars must accept the cache: $output") {
                started shouldBe 0
            }
        }
    }
}
