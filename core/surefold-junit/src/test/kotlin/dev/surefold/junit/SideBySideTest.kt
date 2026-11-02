package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.samples.FirstTurnSuite
import dev.surefold.junit.samples.IsolatedTurnSuite
import dev.surefold.junit.samples.LastTurnSuite
import dev.surefold.junit.samples.Meeting
import dev.surefold.junit.samples.MeetingSuite
import dev.surefold.junit.samples.OtherMeetingSuite
import dev.surefold.junit.samples.Turns
import org.junit.jupiter.api.Test
import org.junit.platform.engine.TestExecutionResult
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineExecutionResults
import org.junit.platform.testkit.engine.EngineTestKit
import java.util.concurrent.CountDownLatch

/** Suites run side by side up to surefold.junit.suites.limit, one at a time by default, and an isolated one alone. */
class SideBySideTest {

    private fun run(limit: String?, vararg suites: Class<*>): EngineExecutionResults =
        EngineTestKit.engine("surefold")
            .also { kit -> limit?.let { kit.configurationParameter(SUITES_LIMIT, it) } }
            .selectors(*suites.map { selectClass(it) }.toTypedArray())
            .execute()

    private fun turns(limit: String?): List<String> {
        Turns.seen.clear()
        run(limit, FirstTurnSuite::class.java, IsolatedTurnSuite::class.java, LastTurnSuite::class.java)
            .testEvents().succeeded().count() shouldBe 3L
        return Turns.seen.toList()
    }

    @Test
    fun `two suites under a limit of two run at once`() {
        Meeting.both = CountDownLatch(2)

        run("2", MeetingSuite::class.java, OtherMeetingSuite::class.java).testEvents().succeeded().count() shouldBe 2L
    }

    @Test
    fun `suites run one after another by default`() {
        turns(null) shouldBe listOf("first in", "first out", "isolated in", "isolated out", "last in", "last out")
    }

    @Test
    fun `an isolated suite runs alone, after those before it and before those after it`() {
        turns("3") shouldBe listOf("first in", "first out", "isolated in", "isolated out", "last in", "last out")
    }

    @Test
    fun `a limit that is not a number of suites of one or more fails the run, saying so`() {
        val failed = run("0", MeetingSuite::class.java).allEvents().failed().list().single()

        // The platform wraps what the engine threw in a failure of its own.
        failed.getRequiredPayload(TestExecutionResult::class.java).throwable.get().cause?.message shouldBe
            "surefold.junit.suites.limit is 0, which is not a number of suites of 1 or more"
    }
}
