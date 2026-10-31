package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.shouldBe
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.Tracer
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.ReadWriteSpan
import io.opentelemetry.sdk.trace.ReadableSpan
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.SpanProcessor
import io.opentelemetry.sdk.trace.samplers.Sampler
import kotlin.time.TimeSource

/** Opt-in diagnostic in the JDBC integration's test sources, where its internal facade is visible. */
object FacadeDiagnosticsSuite : Suite() {
    init {
        if (System.getProperty("surefold.jdbc.diagnostics") == "true") {
            test("diagnostic facade span construction against the original SDK") {
                val completed = Shared(0L)
                val processor = object : SpanProcessor {
                    override fun onStart(parentContext: Context, span: ReadWriteSpan) = Unit
                    override fun isStartRequired() = false
                    override fun onEnd(span: ReadableSpan) { completed.update { it + 1 } }
                    override fun isEndRequired() = true
                    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()
                    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
                }
                val builder = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn()).addSpanProcessor(processor)
                builder.build().use { provider ->
                    val sdk = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
                    val raw = sdk.getTracer("diagnostic")
                    val safe = JdbcPrivacyTelemetry(sdk, setOf("bench_rows")).getTracer("diagnostic")
                    val repetitions = System.getProperty("surefold.jdbc.diagnostic.iterations", "100000").toInt()
                    require(repetitions > 0)
                    repeat(5) { round ->
                        val modes = if (round % 2 == 0) listOf("original" to raw, "facade" to safe)
                        else listOf("facade" to safe, "original" to raw)
                        modes.forEach { (mode, tracer) ->
                            repeat(10_000) { tracer.diagnosticSpan() }
                            val mark = TimeSource.Monotonic.markNow()
                            repeat(repetitions) { tracer.diagnosticSpan() }
                            val nanos = mark.elapsedNow().inWholeNanoseconds
                            println(
                                """{"diagnostic":"facade","round":$round,"mode":"$mode","operations":$repetitions,""" +
                                    """"elapsed_nanos":$nanos,""" +
                                    """"nanos_per_operation":${nanos.toDouble() / repetitions}}""",
                            )
                        }
                    }
                    completed.peek() shouldBe 10L * (10_000 + repetitions)
                }
            }
        }
    }
}

private fun Tracer.diagnosticSpan() {
    spanBuilder("SELECT literal_266 FROM bench_rows").setSpanKind(SpanKind.CLIENT)
        .setAttribute("db.query.summary", "SELECT bench_rows")
        .setAttribute("db.operation.name", "SELECT")
        .setAttribute("db.system.name", "postgresql")
        .setAttribute("db.query.text", "SELECT value FROM bench_rows WHERE id=266")
        .startSpan().end()
}
