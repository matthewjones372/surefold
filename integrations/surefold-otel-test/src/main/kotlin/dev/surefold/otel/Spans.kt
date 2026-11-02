package dev.surefold.otel

import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import java.util.Collections

/**
 * A [tracer] that keeps every span it ends, for a test to read (spec 0221). Make one per test, so a test reads only
 * its own spans, and close it with `use`.
 */
class Spans : AutoCloseable {

    private val ended: MutableList<SpanData> = Collections.synchronizedList(mutableListOf())

    private val provider = SdkTracerProvider.builder().addSpanProcessor(SimpleSpanProcessor.create(Keeping())).build()

    /** The tracer to hand to the code under test. */
    val tracer: Tracer = provider.get("surefold-otel-test")

    /** Every span ended so far, in the order they ended. */
    fun all(): List<SpanData> = synchronized(ended) { ended.toList() }

    /** The spans ended so far that are called [name], in the order they ended. */
    fun named(name: String): List<SpanData> = all().filter { it.name == name }

    /** Shuts the tracer down; a span ended after this is not kept. */
    override fun close() = provider.close()

    private inner class Keeping : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            ended += spans
            return CompletableResultCode.ofSuccess()
        }

        override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

        override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
    }
}
