package dev.surefold.junit.testkit

import dev.surefold.assert.shouldBe
import dev.surefold.junit.testkit.samples.ReportsSuite
import org.junit.jupiter.api.Test
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.testkit.engine.EngineTestKit

class ReportsTest {

    private val events by lazy {
        EngineTestKit.engine("surefold").selectors(selectClass(ReportsSuite::class.java)).execute().testEvents()
    }

    @Test
    fun `logs answers a failed test's lines, one string each, and leaves out a test that published none`() {
        events.logs() shouldBe
            mapOf(
                "logs, then fails" to
                    listOf(
                        "INFO ${ReportsSuite::class.java.name} first",
                        "WARN ${ReportsSuite::class.java.name} second",
                    ),
            )
    }

    @Test
    fun `reported answers what each test reported under a key, and leaves out a test that reported nothing`() {
        events.reported("attempts") shouldBe mapOf("passes on its second attempt" to "2 of 2")
        events.reported("no such key") shouldBe emptyMap()
    }
}
