package dev.surefold.junit

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContain
import dev.surefold.junit.samples.IgnoringSuite
import dev.surefold.junit.samples.MixedSuite
import dev.surefold.junit.samples.UnacquirableSuite
import java.io.PrintWriter
import java.io.StringWriter
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.junit.platform.engine.discovery.DiscoverySelectors.selectClass
import org.junit.platform.launcher.EngineFilter.includeEngines
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder.request
import org.junit.platform.launcher.core.LauncherFactory
import org.junit.platform.reporting.legacy.xml.LegacyXmlReportGeneratingListener

/** The JUnit XML a build tool writes from the platform's events, written here by the platform's own listener. */
class JUnitXmlTest {

    @Test
    fun `a pass, a failure and an error are recorded in JUnit XML under the tests' names`(@TempDir reports: Path) {
        val xml = xmlOf(MixedSuite::class.java, reports)
        xml shouldContain """<testcase name="passes" classname="${MixedSuite::class.java.name}""""
        xml shouldContain """<testcase name="fails an assertion""""
        xml shouldContain """<failure message="expected 1 but was 2" type="java.lang.AssertionError">"""
        xml shouldContain """<testcase name="throws something else""""
        xml shouldContain """<error message="the database was not there" type="java.lang.IllegalStateException">"""
    }

    @Test
    fun `when acquiring fails, each test is recorded with the acquisition's cause under its own name`(
        @TempDir reports: Path,
    ) {
        val xml = xmlOf(UnacquirableSuite::class.java, reports)

        xml shouldContain """<testcase name="first never runs""""
        xml shouldContain """<testcase name="second never runs""""
        xml.split("""<error message="no database" type="java.lang.IllegalStateException">""").size - 1 shouldBe 2
    }

    @Test
    fun `an ignored test is recorded as skipped, with its reason`(@TempDir reports: Path) {
        val xml = xmlOf(IgnoringSuite::class.java, reports)

        val speaks = xml.substringAfter("""<testcase name="speaks HTTP/3"""").substringBefore("</testcase>")
        speaks shouldContain "<skipped>"
        speaks shouldContain "no QUIC on CI"
    }

    private fun xmlOf(suite: Class<*>, reports: Path): String {
        val discovery = request().selectors(selectClass(suite)).filters(includeEngines("surefold")).build()
        val writer = LegacyXmlReportGeneratingListener(reports, PrintWriter(StringWriter()))
        LauncherFactory.create().execute(discovery, writer)
        return reports.resolve("TEST-surefold.xml").readText()
    }
}
