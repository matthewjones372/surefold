package io.github.matthewjones372.surefold.otel

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.withClue
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.context.ContextStorage

/** The collector a service's tests read spans from (spec 0221). */
object SpansSuite : Suite() {
    init {
        test("the spans a tracer ends are kept in the order they ended, and named picks out one name") {
            Spans().use { spans ->
                spans.tracer.spanBuilder("publish").setSpanKind(SpanKind.PRODUCER).startSpan().end()
                spans.tracer.spanBuilder("process").setSpanKind(SpanKind.CONSUMER).startSpan().end()
                spans.tracer.spanBuilder("publish").startSpan().end()

                spans.all().map { it.name } shouldBe listOf("publish", "process", "publish")
                spans.named("process").map { it.kind } shouldBe listOf(SpanKind.CONSUMER)
                spans.named("publish").map { it.kind } shouldBe listOf(SpanKind.PRODUCER, SpanKind.INTERNAL)
            }
        }

        test("a span still open is not kept, and one collector does not see another's spans") {
            Spans().use { mine ->
                Spans().use { theirs ->
                    val open = mine.tracer.spanBuilder("open").startSpan()
                    theirs.tracer.spanBuilder("theirs").startSpan().end()

                    mine.all().shouldBeEmpty()
                    open.end()
                    mine.all().map { it.name } shouldBe listOf("open")
                }
            }
        }

        test("on the classpath with surefold-otel, the storage OpenTelemetry uses is still surefold's") {
            withClue("a second ContextStorageProvider would make OpenTelemetry fall back to its default") {
                ContextStorage.get()::class.java.name shouldBe SurefoldContextStorage::class.java.name
            }
        }
    }
}
