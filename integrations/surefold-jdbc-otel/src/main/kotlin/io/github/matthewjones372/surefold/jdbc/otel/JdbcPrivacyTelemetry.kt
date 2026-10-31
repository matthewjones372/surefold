package io.github.matthewjones372.surefold.jdbc.otel

import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.AttributeType
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanBuilder
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.api.trace.TracerBuilder
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.Context
import io.opentelemetry.context.propagation.ContextPropagators
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

internal class JdbcPrivacyTelemetry(private val delegate: OpenTelemetry, identifiers: Set<String>) : OpenTelemetry {
    private val approved = identifiers.toSet().also { names ->
        require(names.size <= MAX_IDENTIFIERS && names.all { IDENTIFIER.matches(it) }) {
            "Invalid JDBC identifier configuration"
        }
    }

    override fun getPropagators(): ContextPropagators = ContextPropagators.noop()
    override fun getTracerProvider(): TracerProvider = object : TracerProvider {
        override fun get(name: String): Tracer = tracerBuilder(name).build()
        override fun get(name: String, version: String): Tracer =
            tracerBuilder(name).setInstrumentationVersion(version).build()
        override fun tracerBuilder(name: String): TracerBuilder = SafeTracerBuilder(
            telemetryOr(OpenTelemetry.noop().tracerBuilder(name)) { delegate.tracerProvider.tracerBuilder(name) },
            approved,
        )
    }
}

private class SafeTracerBuilder(
    private val delegate: TracerBuilder,
    private val approved: Set<String>,
) : TracerBuilder {
    override fun setSchemaUrl(schemaUrl: String): TracerBuilder = apply {
        telemetryOr(Unit) { delegate.setSchemaUrl(schemaUrl) }
    }
    override fun setInstrumentationVersion(version: String): TracerBuilder = apply {
        telemetryOr(Unit) { delegate.setInstrumentationVersion(version) }
    }
    override fun build(): Tracer {
        val tracer = telemetryOr(OpenTelemetry.noop().getTracer("jdbc")) { delegate.build() }
        return object : Tracer {
            override fun spanBuilder(spanName: String): SpanBuilder = SafeSpanBuilder(tracer, approved)
            override fun isEnabled(): Boolean = telemetryOr(false) { tracer.isEnabled }
        }
    }
}

private class SafeSpanBuilder(private val tracer: Tracer, private val approved: Set<String>) : SpanBuilder {
    private var attributes = Attributes.empty()
    private var stableSystemPresent = false
    private var parent: Context? = null
    private var kind = SpanKind.INTERNAL
    private var timestamp: Pair<Long, TimeUnit>? = null
    private var links = emptyList<SpanContext>()
    override fun setParent(context: Context): SpanBuilder = apply { parent = context }
    override fun setNoParent(): SpanBuilder = setParent(Context.root())
    override fun setSpanKind(spanKind: SpanKind): SpanBuilder = apply { kind = spanKind }
    override fun setStartTimestamp(startTimestamp: Long, unit: TimeUnit): SpanBuilder = apply {
        timestamp = startTimestamp to unit
    }
    override fun addLink(spanContext: SpanContext): SpanBuilder = apply { links = links + spanContext }
    override fun addLink(spanContext: SpanContext, attributes: Attributes): SpanBuilder = addLink(spanContext)
    override fun setAttribute(key: String, value: String?): SpanBuilder =
        setAttribute(AttributeKey.stringKey(key), value)
    override fun setAttribute(key: String, value: Long): SpanBuilder = setAttribute(AttributeKey.longKey(key), value)
    override fun setAttribute(key: String, value: Double): SpanBuilder =
        setAttribute(AttributeKey.doubleKey(key), value)
    override fun setAttribute(key: String, value: Boolean): SpanBuilder =
        setAttribute(AttributeKey.booleanKey(key), value)
    override fun <T : Any?> setAttribute(key: AttributeKey<T>, value: T?): SpanBuilder = apply {
        if (key.key == "db.system.name") stableSystemPresent = true
        if (value != null && key.key in INPUT_KEYS) {
            val updated = attributes.toBuilder().removeIf { it.key == key.key }
            if (key.type == AttributeType.STRING || key.type == AttributeType.LONG) updated.put(key, value)
            attributes = updated.build()
        }
    }
    override fun startSpan(): Span {
        val safe = safeAttributes(attributes, approved, stableSystemPresent)
        val name = checkNotNull(safe.get(AttributeKey.stringKey("db.query.summary")))
        return SafeSpan(
            telemetryOr(Span.getInvalid()) {
                val builder = tracer.spanBuilder(name)
                builder.setAllAttributes(safe).setSpanKind(kind)
                parent?.let { builder.setParent(it) }
                timestamp?.let { (time, unit) -> builder.setStartTimestamp(time, unit) }
                links.forEach { builder.addLink(it, Attributes.empty()) }
                builder.startSpan()
            },
        )
    }
}

/** Query identity is frozen before sampling so later mutations cannot disagree with observers. */
private class SafeSpan(private val delegate: Span) : Span {
    override fun <T : Any?> setAttribute(key: AttributeKey<T>, value: T?): Span = this
    override fun updateName(name: String): Span = this
    override fun addEvent(name: String, attributes: Attributes): Span = this
    override fun addEvent(name: String, attributes: Attributes, timestamp: Long, unit: TimeUnit): Span = this
    override fun recordException(exception: Throwable, additionalAttributes: Attributes): Span = this
    override fun addLink(spanContext: SpanContext, attributes: Attributes): Span = apply {
        telemetryOr(Unit) { delegate.addLink(spanContext, Attributes.empty()) }
    }
    override fun setStatus(statusCode: StatusCode, description: String): Span = apply {
        telemetryOr(Unit) {
            if (statusCode == StatusCode.ERROR) delegate.setAttribute("error.type", "database_error")
            delegate.setStatus(statusCode, "")
        }
    }
    override fun end(): Unit = telemetryOr(Unit) { delegate.end() }
    override fun end(timestamp: Long, unit: TimeUnit): Unit = telemetryOr(Unit) { delegate.end(timestamp, unit) }
    override fun getSpanContext(): SpanContext = telemetryOr(SpanContext.getInvalid()) { delegate.spanContext }
    override fun isRecording(): Boolean = telemetryOr(false) { delegate.isRecording }
}

@Suppress("TooGenericExceptionCaught", "SwallowedException") // SDK failures must not fail database work.
internal inline fun <T> telemetryOr(fallback: T, action: () -> T): T = try {
    action()
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (_: RuntimeException) {
    fallback
}

private val IDENTIFIER = Regex("[a-z_][a-z0-9_]{0,62}")
private const val MAX_IDENTIFIERS = 256
private val INPUT_KEYS = setOf(
    "db.query.summary", "db.operation.name", "db.operation", "db.system.name", "db.system", "db.operation.batch.size",
)
