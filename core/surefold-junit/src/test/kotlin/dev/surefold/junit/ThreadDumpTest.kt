package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.assert.withClue
import dev.surefold.junit.samples.ParkedPastTimeoutSuite
import dev.surefold.junit.samples.SpinningPastTimeoutSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.shouldHaveFailed
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.minutes
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** Spec 0308: a test that times out leaves every thread's stack on stderr, taken before the interrupt. */
class ThreadDumpTest {

    /** What [block] writes to stderr. */
    private fun stderrOf(block: () -> Unit): String {
        val printed = ByteArrayOutputStream()
        val err = System.err
        System.setErr(PrintStream(printed, true))
        try {
            block()
        } finally {
            System.setErr(err)
        }
        return printed.toString()
    }

    @Test
    fun `a timed-out suite test prints the virtual thread it left parked, and fails as before`() {
        val test = "parks a virtual thread and outlives its timeout"
        lateinit var failure: Throwable

        val printed = stderrOf {
            val events =
                EngineTestKit.engine("surefold")
                    .selectors(selectClass(ParkedPastTimeoutSuite::class.java))
                    .execute()
                    .testEvents()
            events shouldHaveFailed listOf(test)
            failure = events.failureOf(test)
        }

        failure.message shouldBe "no answer within 200ms"
        withClue("the dump names the parked virtual thread and the frame it is parked in") {
            printed shouldContain "\"${ParkedPastTimeoutSuite.PARKED}\" virtual"
            printed shouldContain "ParkedPastTimeoutSuite.parkedForTheDump"
        }
        withClue("the dump was taken while the test was still asleep, before the interrupt woke it") {
            printed shouldContain "ParkedPastTimeoutSuite.sleptOnUntilInterrupted"
        }
    }

    @Test
    fun `a test that spins without yielding still fails at its timeout, the spinning frame in the dump`() {
        // testOnOneCarrier runs this with one carrier, where a virtual watchdog would never get to run (spec 0310).
        val test = "spins without yielding and outlives its timeout"
        lateinit var failure: Throwable

        val printed = stderrOf {
            val events =
                EngineTestKit.engine("surefold")
                    .selectors(selectClass(SpinningPastTimeoutSuite::class.java))
                    .execute()
                    .testEvents()
            events shouldHaveFailed listOf(test)
            failure = events.failureOf(test)
        }

        failure.message shouldBe "no answer within 200ms"
        withClue("the dump was taken while the test still spun") {
            printed shouldContain "SpinningPastTimeoutSuite.spinsUntilInterrupted"
        }
    }

    @Test
    fun `a test that answers in time prints nothing`() {
        stderrOf { timeout(1.minutes).run {} } shouldBe ""
    }
}
