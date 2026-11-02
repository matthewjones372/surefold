package dev.surefold.junit.testkit.samples

import dev.surefold.junit.Suite
import dev.surefold.junit.flaky
import dev.surefold.junit.shouldBe
import dev.surefold.logInfo
import dev.surefold.logWarn
import java.util.concurrent.atomic.AtomicInteger

/** Tests that log and report, for the testkit to read. */
object ReportsSuite : Suite() {
    private val attempts = AtomicInteger(0)

    init {
        test("logs, then fails") {
            logInfo("first")
            logWarn("second")
            throw AssertionError("failed")
        }
        test("logs, then passes") {
            logInfo("quiet")
            2 shouldBe 2
        }
        test("passes on its second attempt", flaky(2)) { attempts.incrementAndGet() shouldBe 2 }
    }
}
