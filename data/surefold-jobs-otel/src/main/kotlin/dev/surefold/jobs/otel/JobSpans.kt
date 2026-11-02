package dev.surefold.jobs.otel

import dev.surefold.jobs.JobRunInfo
import dev.surefold.jobs.JobRunWatcher
import io.opentelemetry.api.GlobalOpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import io.opentelemetry.api.trace.Tracer

/**
 * A span per run of a job, named `job <kind>`, in a trace of its own with a link to the span that enqueued it: a job
 * run an hour later in another process is not part of the request's latency, which is OpenTelemetry's own advice for
 * work queued and run later. The handler's spans are its children. Registered through `META-INF/services`, on the
 * global tracer; [tracer] is for a test, or a service that keeps its own.
 */
class JobSpans(private val tracer: () -> Tracer = { GlobalOpenTelemetry.getTracer("surefold-jobs") }) : JobRunWatcher {

    constructor(tracer: Tracer) : this({ tracer })

    override fun around(run: JobRunInfo, block: () -> String): String {
        // The enqueuer's span: a watcher runs inside the context the job carried.
        val enqueuer = Span.current().spanContext
        val builder = tracer().spanBuilder("job ${run.kind}")
            .setNoParent()
            .setSpanKind(SpanKind.CONSUMER)
            .setAttribute("surefold.job.id", run.id.toString())
            .setAttribute("surefold.job.kind", run.kind)
            .setAttribute("surefold.job.attempt", run.attempt.toLong())
        run.tenant?.let { builder.setAttribute("surefold.job.tenant", it) }
        if (enqueuer.isValid) builder.addLink(enqueuer)
        val span = builder.startSpan()
        var result = "threw"
        try {
            result = span.makeCurrent().use { block() }
            return result
        } finally {
            span.setAttribute("surefold.job.result", result)
            if (result == "failed" || result == "threw") span.setStatus(StatusCode.ERROR)
            span.end()
        }
    }
}
