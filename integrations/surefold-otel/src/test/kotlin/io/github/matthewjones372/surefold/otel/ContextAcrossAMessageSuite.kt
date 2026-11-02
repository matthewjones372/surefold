package io.github.matthewjones372.surefold.otel

import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.Tracer
import kotlin.time.Duration.Companion.minutes

private data class Handle(val reply: Reply<String>)

/** A tracer and the spans it finished, made fresh for each test so one test never reads another's. */
private class Traced {
    val exported = Spans()

    val tracer: Tracer = exported.tracer

    fun handler() = behaviour<Handle, Unit>(Unit) { _, _, message ->
        stay().also { message.reply(tracer.span("handled") { Span.current().spanContext.traceId }) }
    }
}

/** Spec 0122: the trace a message was sent in is the one its handler runs in. */
object ContextAcrossAMessageSuite : Suite() {
    init {
        test("a span open at an ask is the parent of the span its handler opens") {
            val traced = Traced()
            val _ = scope {
                val actor = spawn("handler", traced.handler())
                traced.tracer.span("asking") { actor.ask(1.minutes) { Handle(it) }.getOrNull()!! }
            }

            val spans = traced.exported.all()
            val asking = spans.single { it.name == "asking" }
            val handled = spans.single { it.name == "handled" }
            withClue("without the carrier, the handler's span is a root of its own") {
                handled.traceId shouldBe asking.traceId
                handled.parentSpanId shouldBe asking.spanId
            }
        }

        test("a message sent outside any span is handled outside one") {
            val traced = Traced()
            val _ = scope {
                spawn("handler", traced.handler()).ask(1.minutes) { Handle(it) }.getOrNull()!!
            }

            traced.exported.all().single { it.name == "handled" }.parentSpanId shouldBe "0000000000000000"
        }
    }
}
