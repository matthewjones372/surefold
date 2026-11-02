package dev.surefold.otel

import dev.surefold.logAnnotated
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * A span around [block], ended whatever the block did. A throw is recorded on the span and rethrown,
 * because a span that ends `Ok` on a failure is worse than no span.
 */
fun <A> Tracer.span(name: String, block: () -> A): A {
    val span = spanBuilder(name).startSpan()
    return try {
        span.makeCurrent().use { block() }
    } catch (thrown: Throwable) {
        span.setStatus(StatusCode.ERROR)
        span.recordException(thrown)
        throw thrown
    } finally {
        span.end()
    }
}

/**
 * [span], with the trace and span ids on every log line written inside it, so a line found in a log
 * says which trace to open and a trace says which lines to read.
 */
fun <A> Tracer.tracedSpan(name: String, block: () -> A): A = span(name) {
    val context = Span.current().spanContext
    logAnnotated("trace_id" to context.traceId, "span_id" to context.spanId, block = block)
}
