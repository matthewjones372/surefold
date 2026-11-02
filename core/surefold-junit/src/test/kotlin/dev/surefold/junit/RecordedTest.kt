package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.samples.RecordedSuite
import dev.surefold.junit.testkit.failureOf
import dev.surefold.junit.testkit.shouldHaveSucceeded
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

/** A test's result is every check it made, recorded as it was made. */
class RecordedTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(RecordedSuite::class.java)).execute().testEvents()
    }

    private fun headingOf(test: String): String = events.failureOf(test).message.orEmpty().lines().first()

    @Test
    fun `each check written as a statement counts, and each failure is reported`() {
        headingOf("records each check written as a statement") shouldBe
            "records each check written as a statement: 2 of 3 expectations failed (2 failures)"
    }

    @Test
    fun `a test that checks nothing fails, saying so`() {
        headingOf("fails a test that checks nothing") shouldBe "fails a test that checks nothing: no checks were made"
    }

    @Test
    fun `a story step left out of any value still counts`() {
        headingOf("counts a story step left out of any value") shouldBe
            "counts a story step left out of any value: 1 of 2 expectations failed"
    }

    @Test
    fun `a helper calls the block it is handed as a statement, and each check it makes counts`() {
        headingOf("records each check a helper calls with the block it is handed") shouldBe
            "records each check a helper calls with the block it is handed: 1 of 2 expectations failed"
    }

    @Test
    fun `a body may end in a call of a block that checks`() {
        headingOf("records a check handed to the body's last line") shouldBe
            "records a check handed to the body's last line: 1 of 1 expectations failed"
    }

    @Test
    fun `a check made on a fork counts as the test's`() {
        headingOf("records a check made on a fork") shouldBe
            "records a check made on a fork: 1 of 1 expectations failed"
    }

    @Test
    fun `a check made on a thread surefold does not carry throws there, and is not lost silently`() {
        events shouldHaveSucceeded
            listOf("throws a check made on a thread surefold does not carry, there")
    }
}
