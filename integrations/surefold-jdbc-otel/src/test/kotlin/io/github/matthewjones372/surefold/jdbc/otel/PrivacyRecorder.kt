package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.data.LinkData
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import io.opentelemetry.sdk.trace.samplers.SamplingResult

internal class PrivacyRecorder(
    identifiers: Set<String> = setOf("bookings"),
    samplingFailure: Throwable? = null,
) : AutoCloseable {
    val sampled = Shared(emptyList<SampledQuery>())
    val started = Shared(emptyList<SpanData>())
    val ended = Shared(emptyList<SpanData>())
    val exported = Shared(emptyList<SpanData>())
    private val provider = SdkTracerProvider.builder()
        .setSampler(object : Sampler {
            override fun shouldSample(
                parentContext: Context,
                traceId: String,
                name: String,
                spanKind: SpanKind,
                attributes: Attributes,
                parentLinks: List<LinkData>,
            ): SamplingResult {
                if (samplingFailure != null) throw samplingFailure
                sampled.update { it + SampledQuery(name, attributes.toBuilder().build(), parentLinks.toList()) }
                return SamplingResult.recordAndSample()
            }

            override fun getDescription(): String = "privacy test"
        })
        .addSpanProcessor(object : SpanProcessor {
            override fun onStart(parentContext: Context, span: ReadWriteSpan) {
                started.update { it + span.toSpanData() }
            }

            override fun isStartRequired(): Boolean = true

            override fun onEnd(span: ReadableSpan) {
                ended.update { it + span.toSpanData() }
            }

            override fun isEndRequired(): Boolean = true

            override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

            override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
        })
        .addSpanProcessor(
            SimpleSpanProcessor.create(object : SpanExporter {
                override fun export(spans: Collection<SpanData>): CompletableResultCode {
                    exported.update { it + spans }
                    return CompletableResultCode.ofSuccess()
                }

                override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

                override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
            }),
        )
        .build()
    val sdk: OpenTelemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
    val telemetry: OpenTelemetry = JdbcPrivacyTelemetry(sdk, identifiers)
    val tracer: Tracer = telemetry.getTracer("jdbc-test")

    fun assertNoMarker() {
        listOf(sampled.peek(), started.peek(), ended.peek(), exported.peek()).forEach {
            expect(!it.toString().contains(PRIVATE_MARKER))
        }
        sampled.peek().forEach { assertSafeFields(it.name, it.attributes) }
        (started.peek() + ended.peek() + exported.peek()).forEach { assertSafeFields(it.name, it.attributes) }
    }

    override fun close() = provider.close()
}

internal data class SampledQuery(val name: String, val attributes: Attributes, val links: List<LinkData>)

internal const val PRIVATE_MARKER = "private-password-person@example.invalid"

private fun assertSafeFields(name: String, attributes: Attributes) {
    val allowed = setOf(
        "db.query.summary", "db.operation.name", "db.system.name", "db.operation.batch.size", "error.type",
    )
    expect(attributes.asMap().keys.all { it.key in allowed })
    attributes.get(AttributeKey.stringKey("db.query.summary")) shouldBe name
    if (name == "DB query") attributes.get(AttributeKey.stringKey("db.operation.name")) shouldBe null
    else attributes.get(AttributeKey.stringKey("db.operation.name")) shouldBe name.substringBefore(' ')
}
