package io.github.matthewjones372.surefold.jdbc.otel

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes

internal fun safeAttributes(input: Attributes, approved: Set<String>, stableSystemPresent: Boolean): Attributes {
    fun text(key: String): String? = input.get(AttributeKey.stringKey(key))
    val summary = text("db.query.summary")
    val operation = listOf(
        text("db.operation.name"), text("db.operation"), summary?.take(OPERATION_PREFIX_LIMIT)?.substringBefore(' '),
    )
        .firstOrNull { it in OPERATIONS }
    val name = validSummary(summary, approved) ?: operation ?: "DB query"
    val result = Attributes.builder().put("db.query.summary", name)
    if (name != "DB query") result.put("db.operation.name", name.substringBefore(' '))
    val system = if (stableSystemPresent) text("db.system.name")
    else text("db.system")?.let { LEGACY_SYSTEMS[it] ?: it }
    if (system in SYSTEMS) result.put("db.system.name", system)
    input.get(AttributeKey.longKey("db.operation.batch.size"))?.takeIf { it in 0..MAX_BATCH }?.let {
        result.put("db.operation.batch.size", it)
    }
    return result.build()
}

private fun validSummary(summary: String?, approved: Set<String>): String? {
    if (summary == null || summary.length > MAX_SUMMARY) return null
    val parts = summary.split(' ')
    if (parts.first() !in OPERATIONS) return null
    return when (parts.size) {
        1 -> summary
        2 -> summary.takeIf { parts.last() in approved }
        else -> null
    }
}

private const val MAX_SUMMARY = 72
private const val OPERATION_PREFIX_LIMIT = 9
private const val MAX_BATCH = 1_000_000L
private val OPERATIONS = setOf(
    "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL", "CREATE", "ALTER", "DROP", "TRUNCATE", "BATCH",
)
private val SYSTEMS = setOf(
    "postgresql", "mysql", "mariadb", "oracle.db", "microsoft.sql_server", "h2database", "sqlite", "other_sql",
)
private val LEGACY_SYSTEMS = mapOf("h2" to "h2database", "mssql" to "microsoft.sql_server", "oracle" to "oracle.db")
