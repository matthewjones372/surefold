package io.github.matthewjones372.surefold.otel

import io.github.matthewjones372.surefold.Carrier
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.TextMapGetter
import io.opentelemetry.context.propagation.TextMapSetter

/**
 * The trace a message was sent in, carried to whoever handles it (spec 0122): `traceparent` and
 * `tracestate`, written and read as W3C says, so the same map crosses a node unchanged.
 *
 * W3C's propagator rather than the one a service configured: a message is not an HTTP call, and the
 * two ends are both surefold, so there is nothing to agree with but itself. Nothing is captured outside a
 * span, so a message sent with no trace costs what it did before.
 */
class TraceCarrier : Carrier {

    override fun capture(): Map<String, String> {
        val context = otelContext.get()
        if (!Span.fromContext(context).spanContext.isValid) return emptyMap()
        val carried = HashMap<String, String>(2)
        W3CTraceContextPropagator.getInstance().inject(context, carried, Setter)
        return carried
    }

    override fun <A> within(carried: Map<String, String>, block: () -> A): A {
        if (TRACEPARENT !in carried) return block()
        val context = W3CTraceContextPropagator.getInstance().extract(Context.root(), carried, Getter)
        return otelContext.locally(context, block)
    }

    private object Setter : TextMapSetter<HashMap<String, String>> {
        override fun set(carrier: HashMap<String, String>?, key: String, value: String) {
            carrier?.put(key, value)
        }
    }

    private object Getter : TextMapGetter<Map<String, String>> {
        override fun get(carrier: Map<String, String>?, key: String): String? = carrier?.get(key)
    }

    private companion object {
        const val TRACEPARENT = "traceparent"
    }
}
