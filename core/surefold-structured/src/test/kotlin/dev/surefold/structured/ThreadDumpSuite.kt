package dev.surefold.structured

import com.sun.management.HotSpotDiagnosticMXBean
import dev.surefold.fail.attempt
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.withClue
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.util.concurrent.CountDownLatch

object ThreadDumpSuite : Suite() {
    // The JSON dump's own spelling: every container names its parent by the same identifier.
    private val container = Regex(""""container": "([^"]+)",\s*"parent": ("[^"]+"|null)""")

    init {
        test("a thread dump shows each branch under the scope of the call that forked it") {
            `a thread dump shows each branch under the scope of the call that forked it`()
        }
    }

    // A scope is named after the function that opened it, and a test's lambda has no name of its own: the test
    // forks from a function named as the test is, as a Jupiter method was, so the name is what the check reads.
    @Suppress("ktlint:standard:function-naming")
    private fun `a thread dump shows each branch under the scope of the call that forked it`() {
        val waiting = CountDownLatch(1)
        val dumped = CountDownLatch(1)
        val dump =
            attempt<Nothing, String> {
                    parZip(
                        {
                            waiting.countDown()
                            dumped.await()
                        },
                        {
                            // A scope of its own inside this branch, so the dump has a nesting to show.
                            parZip(
                                {
                                    waiting.await()
                                    dumpThreads().also { dumped.countDown() }
                                },
                                { 0 },
                            ) { d, _ ->
                                d
                            }
                        },
                    ) { _, d ->
                        d
                    }
                }
                .getOrNull()!!

        val caller = "ThreadDumpSuite.a thread dump shows each branch under the scope of the call that forked it"
        val parents = container.findAll(dump).associate { it.groupValues[1] to it.groupValues[2].trim('"') }
        val outer = parents.keys.single { it.startsWith("$caller\\/jdk") }
        val nested = parents.keys.single { it.startsWith("$caller\\/2\\/jdk") }
        val names = Regex(""""name": "([^"]+)"""").findAll(dump).map { it.groupValues[1] }.toList()
        withClue(dump) {
            parents[nested] shouldBe outer
            names shouldContainAll listOf("$caller\\/1", "$caller\\/2", "$caller\\/2\\/1")
        }
    }

    private fun dumpThreads(): String {
        val file = Files.createTempDirectory("surefold-structured").resolve("threads.json")
        ManagementFactory.getPlatformMXBean(HotSpotDiagnosticMXBean::class.java)
            .dumpThreads(file.toString(), HotSpotDiagnosticMXBean.ThreadDumpFormat.JSON)
        return Files.readString(file)
    }
}
