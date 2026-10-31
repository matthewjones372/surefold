package io.github.matthewjones372.surefold.jdbc.otel

import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.junit.Suite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.junit.shouldThrow
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.logs.LoggerProvider
import io.opentelemetry.api.metrics.MeterProvider
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.api.trace.TracerProvider
import io.opentelemetry.context.Context
import java.time.Instant
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit

object JdbcPrivacyBoundarySuite : Suite() {
    init {
        test("every OpenTelemetry and provider tracer acquisition path filters before SDK observers") {
            PrivacyRecorder().use { recording ->
                val otel = recording.telemetry
                val tracers = listOf(
                    otel.getTracer("jdbc"), otel.getTracer("jdbc", "1"),
                    otel.tracerBuilder("jdbc").setInstrumentationVersion("1")
                        .setSchemaUrl("https://example.invalid/schema").build(),
                    otel.tracerProvider.get("jdbc"), otel.tracerProvider.get("jdbc", "1"),
                    otel.tracerProvider.tracerBuilder("jdbc").setInstrumentationVersion("1")
                        .setSchemaUrl("https://example.invalid/schema").build(),
                )
                tracers.forEach { tracer ->
                    tracer.spanBuilder(PRIVATE_MARKER).setSpanKind(SpanKind.CLIENT)
                        .setAllAttributes(
                            Attributes.builder().put("db.query.summary", "SELECT bookings")
                                .put("db.query.text", PRIVATE_MARKER).put("server.address", PRIVATE_MARKER).build(),
                        )
                        .startSpan().end()
                }
                recording.sampled.peek().size shouldBe tracers.size
                recording.started.peek().size shouldBe tracers.size
                recording.ended.peek().size shouldBe tracers.size
                recording.exported.peek().size shouldBe tracers.size
                recording.sampled.peek().map { it.name }.toSet() shouldBe setOf("SELECT bookings")
                recording.started.peek().map { it.name }.toSet() shouldBe setOf("SELECT bookings")
                recording.exported.peek().map { it.kind }.toSet() shouldBe setOf(SpanKind.CLIENT)
                recording.assertNoMarker()
            }
        }

        test("fluent calls and current context retain the filtering span and identity stays coherent") {
            PrivacyRecorder().use { recording ->
                val span = recording.tracer.spanBuilder(PRIVATE_MARKER)
                    .setAttribute("db.query.summary", "SELECT bookings").startSpan()
                (span.setAttribute("db.query.text", PRIVATE_MARKER) === span) shouldBe true
                (span.setAttribute("unknown.number", 42L) === span) shouldBe true
                (span.setAttribute("unknown.boolean", true) === span) shouldBe true
                val user = Attributes.of(AttributeKey.stringKey("db.user"), PRIVATE_MARKER)
                (span.setAllAttributes(user) === span) shouldBe true
                (span.updateName(PRIVATE_MARKER) === span) shouldBe true
                span.makeCurrent().use {
                    (Span.current() === span) shouldBe true
                    Span.current().setAttribute("db.connection_string", PRIVATE_MARKER)
                    Span.current().updateName("UPDATE bookings")
                }
                (Span.fromContext(Context.root().with(span)) === span) shouldBe true
                span.setAttribute("db.query.summary", "DELETE bookings")
                    .setAttribute("db.operation.name", "DELETE").end()
                val data = recording.exported.peek().single()
                data.attributes.get(AttributeKey.stringKey("db.query.summary")) shouldBe data.name
                val operation = data.name.substringBefore(' ')
                data.attributes.get(AttributeKey.stringKey("db.operation.name")) shouldBe operation
                recording.assertNoMarker()
            }
        }

        test("event exception and link attributes disappear while ids parent and timestamps survive") {
            PrivacyRecorder().use { recording ->
                val parent = testSpanContext("11111111111111111111111111111111", "1111111111111111")
                val link = testSpanContext("22222222222222222222222222222222", "2222222222222222")
                val start = Instant.ofEpochSecond(100)
                val end = Instant.ofEpochSecond(101)
                val span = recording.tracer.spanBuilder(PRIVATE_MARKER)
                    .setParent(Context.root().with(Span.wrap(parent)))
                    .setStartTimestamp(start)
                    .addLink(link, Attributes.of(AttributeKey.stringKey("private.link"), PRIVATE_MARKER))
                    .setAttribute("db.query.summary", "SELECT bookings").startSpan()
                (span.addEvent(PRIVATE_MARKER) === span) shouldBe true
                span.addEvent(PRIVATE_MARKER, Attributes.empty())
                span.addEvent(PRIVATE_MARKER, start)
                span.addEvent(PRIVATE_MARKER, Attributes.empty(), start)
                span.addEvent(PRIVATE_MARKER, 100L, TimeUnit.SECONDS)
                span.addEvent(PRIVATE_MARKER, Attributes.empty(), 100L, TimeUnit.SECONDS)
                (span.recordException(IllegalStateException(PRIVATE_MARKER)) === span) shouldBe true
                span.recordException(
                    IllegalStateException(PRIVATE_MARKER),
                    Attributes.of(AttributeKey.stringKey("exception.secret"), PRIVATE_MARKER),
                )
                span.addLink(link, Attributes.of(AttributeKey.stringKey("private.link"), PRIVATE_MARKER))
                span.setStatus(StatusCode.ERROR, PRIVATE_MARKER)
                span.setAttribute("error.type", PRIVATE_MARKER)
                span.end(end)
                val data = recording.exported.peek().single()
                data.traceId shouldBe parent.traceId
                data.parentSpanId shouldBe parent.spanId
                expect(data.spanId != parent.spanId)
                data.startEpochNanos shouldBe 100_000_000_000L
                data.endEpochNanos shouldBe 101_000_000_000L
                data.events shouldBe emptyList()
                data.links.size shouldBe 2
                data.links.forEach { it.spanContext shouldBe link; it.attributes shouldBe Attributes.empty() }
                data.status.statusCode shouldBe StatusCode.ERROR
                data.status.description shouldBe ""
                data.attributes.get(AttributeKey.stringKey("error.type")) shouldBe "database_error"
                recording.sampled.peek().single().links.single().attributes shouldBe Attributes.empty()
                recording.assertNoMarker()
            }
        }

        test("a builder created under one parent takes the parent current when started") {
            PrivacyRecorder().use { recording ->
                val first = testSpanContext("33333333333333333333333333333333", "3333333333333333")
                val second = testSpanContext("44444444444444444444444444444444", "4444444444444444")
                val builder = Context.root().with(Span.wrap(first)).makeCurrent().use {
                    recording.tracer.spanBuilder(PRIVATE_MARKER).setAttribute("db.operation.name", "SELECT")
                }
                Context.root().with(Span.wrap(second)).makeCurrent().use { builder.startSpan().end() }
                val data = recording.exported.peek().single()
                data.traceId shouldBe second.traceId
                data.parentSpanId shouldBe second.spanId
                recording.tracer.spanBuilder(PRIVATE_MARKER).setNoParent().startSpan().end()
                recording.exported.peek().last().parentSpanContext.isValid shouldBe false
            }
        }

        test("error type is generated from ERROR status and never from caller attributes") {
            PrivacyRecorder().use { recording ->
                listOf(StatusCode.UNSET, StatusCode.OK, StatusCode.ERROR).forEach { status ->
                    recording.tracer.spanBuilder(PRIVATE_MARKER)
                        .setAttribute("error.type", PRIVATE_MARKER).startSpan()
                        .setAttribute("error.type", "database_error").setStatus(status, PRIVATE_MARKER).end()
                }
                recording.exported.peek().map { it.attributes.get(AttributeKey.stringKey("error.type")) } shouldBe
                    listOf(null, null, "database_error")
                recording.exported.peek().forEach { it.status.description shouldBe "" }
                recording.assertNoMarker()
            }
        }

        test("logs and meters never consult the supplied SDK providers") {
            val calls = Shared(0)
            val supplied = object : OpenTelemetry by OpenTelemetry.noop() {
                override fun getLogsBridge(): LoggerProvider { calls.update { it + 1 }; error(PRIVATE_MARKER) }
                override fun getMeterProvider(): MeterProvider { calls.update { it + 1 }; error(PRIVATE_MARKER) }
            }
            val privacy = JdbcPrivacyTelemetry(supplied, emptySet())
            privacy.logsBridge.get("jdbc").logRecordBuilder().setBody(PRIVATE_MARKER).emit()
            privacy.meterProvider.get("jdbc").counterBuilder(PRIVATE_MARKER).build().add(1)
            privacy.getMeter("jdbc").histogramBuilder(PRIVATE_MARKER).build().record(1.0)
            calls.peek() shouldBe 0
        }

        test("a no-op SDK remains disabled through both tracer acquisition paths") {
            val privacy = JdbcPrivacyTelemetry(OpenTelemetry.noop(), emptySet())
            privacy.getTracer("jdbc").isEnabled shouldBe false
            privacy.tracerBuilder("jdbc").build().isEnabled shouldBe false
        }

        test("ordinary telemetry acquisition failures are safe while cancellation interruption and Errors propagate") {
            JdbcPrivacyTelemetry(failingTelemetry(IllegalStateException(PRIVATE_MARKER)), emptySet())
                .getTracer("jdbc").spanBuilder(PRIVATE_MARKER).startSpan().end()
            val cancellation = CancellationException(PRIVATE_MARKER)
            shouldThrow<CancellationException>({
                JdbcPrivacyTelemetry(failingTelemetry(cancellation), emptySet()).getTracer("jdbc")
                    .spanBuilder(PRIVATE_MARKER).startSpan().end()
            }) { (it === cancellation) shouldBe true }
            val interrupted = InterruptedException(PRIVATE_MARKER)
            shouldThrow<InterruptedException>({
                JdbcPrivacyTelemetry(failingTelemetry(interrupted), emptySet()).getTracer("jdbc")
                    .spanBuilder(PRIVATE_MARKER).startSpan().end()
            }) { (it === interrupted) shouldBe true }
            val fatal = LinkageError(PRIVATE_MARKER)
            shouldThrow<LinkageError>({
                JdbcPrivacyTelemetry(failingTelemetry(fatal), emptySet()).getTracer("jdbc")
                    .spanBuilder(PRIVATE_MARKER).startSpan().end()
            }) { (it === fatal) shouldBe true }
        }

        test("sampler failures do not escape unless they cancel interrupt or signal a fatal error") {
            PrivacyRecorder(samplingFailure = IllegalStateException(PRIVATE_MARKER)).use { recording ->
                recording.tracer.spanBuilder(PRIVATE_MARKER).startSpan().end()
                recording.exported.peek() shouldBe emptyList()
            }
            val cancellation = CancellationException(PRIVATE_MARKER)
            PrivacyRecorder(samplingFailure = cancellation).use { recording ->
                shouldThrow<CancellationException>({ recording.tracer.spanBuilder(PRIVATE_MARKER).startSpan() }) {
                    (it === cancellation) shouldBe true
                }
            }
            val interrupted = InterruptedException(PRIVATE_MARKER)
            PrivacyRecorder(samplingFailure = interrupted).use { recording ->
                shouldThrow<InterruptedException>({ recording.tracer.spanBuilder(PRIVATE_MARKER).startSpan() }) {
                    (it === interrupted) shouldBe true
                }
            }
            val fatal = LinkageError(PRIVATE_MARKER)
            PrivacyRecorder(samplingFailure = fatal).use { recording ->
                shouldThrow<LinkageError>({ recording.tracer.spanBuilder(PRIVATE_MARKER).startSpan() }) {
                    (it === fatal) shouldBe true
                }
            }
        }

        test("the privacy boundary does not promise to filter a caller's enclosing span") {
            PrivacyRecorder().use { recording ->
                val parent = recording.sdk.getTracer("caller").spanBuilder("caller").startSpan()
                parent.makeCurrent().use {
                    recording.tracer.spanBuilder(PRIVATE_MARKER).startSpan().end()
                    parent.recordException(IllegalStateException(PRIVATE_MARKER))
                }
                parent.end()
                recording.exported.peek().first().events shouldBe emptyList()
                expect(recording.exported.peek().last().events.toString().contains(PRIVATE_MARKER))
            }
        }
    }
}

private fun testSpanContext(traceId: String, spanId: String): SpanContext =
    SpanContext.create(traceId, spanId, TraceFlags.getSampled(), TraceState.getDefault())

private fun failingTelemetry(failure: Throwable): OpenTelemetry = object : OpenTelemetry by OpenTelemetry.noop() {
    override fun getTracerProvider(): TracerProvider = throw failure
}
