package io.github.matthewjones372.surefold.otel

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.withClue
import dev.surefold.logInfo
import dev.surefold.parMap
import io.opentelemetry.api.trace.Span
import io.opentelemetry.context.Context
import io.opentelemetry.context.ContextStorage

/** What a trace does when the work forks, which is the thing a ThreadLocal cannot answer. */
object ContextAcrossAForkSuite : Suite() {
    init {
        test("the storage OpenTelemetry found is surefold's") {
            withClue("META-INF/services must name SurefoldContextStorageProvider") {
                ContextStorage.get()::class.java.name shouldBe SurefoldContextStorage::class.java.name
            }
        }

        test("a context bound outside a parMap is current inside every branch") {
            val tracer = Spans().tracer
            val outside = Context.root().with(tracer.spanBuilder("outer").startSpan())

            val seen = otelContext.locally(outside) { parMap(listOf(1, 2, 3)) { Context.current() } }

            seen shouldHaveSize 3
            seen.forAll { branch -> branch shouldBe outside }
        }

        test("a span opened before a fork is the parent of what each branch opens") {
            val tracing = Spans()
            val tracer = tracing.tracer
            val _ = tracer.span("outer") {
                parMap(listOf(1, 2)) { tracer.span("branch-$it") { Span.current().spanContext.spanId } }
            }

            val spans = tracing.all()
            val outer = spans.single { it.name == "outer" }
            val branches = spans.filter { it.name.startsWith("branch-") }

            branches shouldHaveSize 2
            withClue("a ThreadLocal would have made each branch a root of its own") {
                branches.forAll { branch -> branch.parentSpanId shouldBe outer.spanId }
            }
        }

        test("attach puts back what it replaced") {
            val first = Context.root().with(Spans().tracer.spanBuilder("first").startSpan())
            val before = Context.current()

            val detach = otelContext.attach(first)
            Context.current() shouldBe first
            detach.detach()

            Context.current() shouldBe before
        }

        test("a traced span puts the trace on every line, including a branch's") {
            val tracer = Spans().tracer
            val lines = capturingLogs { logs ->
                val _ = tracer.tracedSpan("register") { parMap(listOf(1, 2)) { logInfo("branch $it") } }
                logs.all()
            }

            lines shouldHaveSize 2
            lines.forAll { line ->
                expect(line.annotations["trace_id"].orEmpty().isNotBlank())
                expect(line.annotations["span_id"].orEmpty().isNotBlank())
            }
        }
    }
}
