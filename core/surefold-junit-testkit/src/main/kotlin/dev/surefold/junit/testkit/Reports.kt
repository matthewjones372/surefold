package dev.surefold.junit.testkit

import org.junit.platform.engine.reporting.ReportEntry
import org.junit.platform.testkit.engine.EventType
import org.junit.platform.testkit.engine.Events

/** Each test's log lines, by name, one string per line as the engine rendered them; one that logged none is absent. */
fun Events.logs(): Map<String, List<String>> = entries()
    .filter { (_, entry) -> "logs" in entry.keyValuePairs }
    .groupBy({ (test, _) -> test }, { (_, entry) -> entry.keyValuePairs.getValue("logs").lines() })
    .mapValues { (_, published) -> published.flatten() }

/** What each test reported under [key], by name; the last value wins for a test that reported it twice. */
fun Events.reported(key: String): Map<String, String> = entries()
    .mapNotNull { (test, entry) -> entry.keyValuePairs[key]?.let { test to it } }
    .toMap()

private fun Events.entries(): List<Pair<String, ReportEntry>> = list()
    .filter { it.type == EventType.REPORTING_ENTRY_PUBLISHED }
    .map { it.testDescriptor.displayName to it.getRequiredPayload(ReportEntry::class.java) }
