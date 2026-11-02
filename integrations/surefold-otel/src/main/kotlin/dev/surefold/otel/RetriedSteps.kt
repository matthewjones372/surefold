package dev.surefold.otel

import dev.surefold.RetryWatcher
import io.opentelemetry.api.common.Attributes
import io.opentelemetry.api.trace.Span

/**
 * A step that ran more than once adds `transact.retried` to the span it ran in (spec 0212): its name, how many runs it
 * took, `retries.<cause>` for each value it lost to, and `solo` when it ran alone in the end (spec 0231). One event
 * when it commits, rather than one per run, so a contended step does not crowd its span.
 */
class RetriedSteps : RetryWatcher {

    override fun committed(transact: String, attempts: Int, causes: Map<String, Int>, solo: Boolean) {
        val span = Span.fromContext(otelContext.get())
        if (!span.isRecording) return
        val attributes = Attributes.builder()
            .put("transact", transact)
            .put("attempts", attempts.toLong())
        causes.forEach { (cause, count) -> attributes.put("retries.$cause", count.toLong()) }
        if (solo) attributes.put("solo", true)
        span.addEvent("transact.retried", attributes.build())
    }
}
