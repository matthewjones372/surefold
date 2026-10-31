package io.github.matthewjones372.surefold.jdbc.otel.guide

import io.github.matthewjones372.surefold.jdbc.otel.traced
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

fun jdbcTelemetry(exporter: SpanExporter): OpenTelemetrySdk {
    val processor = BatchSpanProcessor.builder(exporter)
        .setMaxQueueSize(4096)
        .setMaxExportBatchSize(512)
        .setScheduleDelay(200, TimeUnit.MILLISECONDS)
        .setExporterTimeout(1, TimeUnit.SECONDS)
        .build()
    val provider = SdkTracerProvider.builder()
        .setSampler(Sampler.alwaysOn())
        .addSpanProcessor(processor)
        .build()
    return OpenTelemetrySdk.builder().setTracerProvider(provider).build()
}

fun tracedDatabase(pool: DataSource, telemetry: OpenTelemetry): DataSource =
    pool.traced(telemetry, identifiers = setOf("bookings", "payments"))

fun tracingDisabled(pool: DataSource, telemetry: OpenTelemetry): DataSource {
    val disabled = pool.traced(telemetry, enabled = false)
    check(disabled === pool)
    return disabled
}

fun flushJdbcTelemetry(telemetry: OpenTelemetrySdk) {
    check(telemetry.sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess)
}
