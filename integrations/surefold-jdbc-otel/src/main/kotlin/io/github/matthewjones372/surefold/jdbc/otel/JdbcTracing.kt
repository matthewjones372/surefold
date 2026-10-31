package io.github.matthewjones372.surefold.jdbc.otel

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.instrumentation.jdbc.datasource.JdbcTelemetry
import javax.sql.DataSource

/** Traces executions with private query summaries; the caller retains ownership of the source. */
fun DataSource.traced(
    openTelemetry: OpenTelemetry,
    enabled: Boolean = true,
    identifiers: Set<String> = emptySet(),
): DataSource {
    if (!enabled) return this
    val privacy = JdbcPrivacyTelemetry(openTelemetry, identifiers)
    return telemetryOr(this) {
        val telemetry = JdbcTelemetry.builder(privacy)
            .setStatementInstrumenterEnabled(true)
            .setDataSourceInstrumenterEnabled(false)
            .setTransactionInstrumenterEnabled(false)
            .setQuerySanitizationEnabled(true)
            .setCaptureQueryParameters(false)
            .build()
        JdbcDataSource(this, telemetry)
    }
}
