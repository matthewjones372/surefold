package dev.surefold.jobs.otel

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate
import dev.surefold.jobs.JobCodec
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobKind
import dev.surefold.jobs.JobResult
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
import dev.surefold.jobs.Jobs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.otel.Spans
import io.github.matthewjones372.surefold.otel.span
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanKind
import io.opentelemetry.api.trace.StatusCode
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private object Text : JobCodec<String> {
    override val version = 1

    override fun encode(payload: String) = "\"$payload\""

    override fun decode(version: Int, json: String) = json.trim('"')
}

private object Remind : JobKind<String>("remind", Text)

/** A span per run, in a trace of its own linked to the enqueuer's (spec 0220). */
object JobSpansSuite : Suite() {
    init {
        test("a job enqueued in a span runs in a new trace whose root links to it, with the handler's span a child") {
            Spans().use { spans ->
                val source = database()
                val handlers = JobHandlers.of(
                    Remind.handledBy { JobResult.Done.also { spans.tracer.span("send") { } } },
                )
                val enqueued = spans.tracer.span("place order") {
                    source.connection.use { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
                    Span.current().spanContext
                }
                JobWorker(source, handlers, quick, watchers = listOf(JobSpans(spans.tracer))).start().use { _ ->
                    eventually(within = 30.seconds) { spans.named("job remind").size shouldBe 1 }
                }
                val run = spans.named("job remind").single()
                run.parentSpanContext.isValid shouldBe false
                (run.traceId == enqueued.traceId) shouldBe false
                // Read back from the job's row, so it arrives as a remote context with the same ids.
                run.links.single().spanContext.let { it.traceId to it.spanId } shouldBe
                    (enqueued.traceId to enqueued.spanId)
                run.kind shouldBe SpanKind.CONSUMER
                run.attributes.asMap().mapKeys { it.key.key }.filterKeys { it != "surefold.job.id" } shouldBe mapOf(
                    "surefold.job.kind" to "remind",
                    "surefold.job.attempt" to 1L,
                    "surefold.job.tenant" to "acme",
                    "surefold.job.result" to "done",
                )
                spans.named("send").single().parentSpanId shouldBe run.spanId
            }
        }

        test("a failed run's span is marked as an error") {
            Spans().use { spans ->
                val source = database()
                val handlers = JobHandlers.of(Remind.handledBy { JobResult.Fail("no such customer") })
                source.connection.use { Jobs.enqueue(it, Remind, "call sam", "sam") }
                JobWorker(source, handlers, quick, watchers = listOf(JobSpans(spans.tracer))).start().use { _ ->
                    eventually(within = 30.seconds) { spans.named("job remind").size shouldBe 1 }
                }
                val run = spans.named("job remind").single()
                run.status.statusCode shouldBe StatusCode.ERROR
                run.links shouldBe emptyList()
            }
        }

        test("JobSpans is registered for the worker to find") {
            javaClass.classLoader.getResource(
                "META-INF/services/dev.surefold.jobs.JobRunWatcher",
            )!!.readText().trim() shouldBe "dev.surefold.jobs.otel.JobSpans"
        }
    }

    private val quick = JobSettings(poll = 20.milliseconds)

    private fun database(): DataSource = Postgres.empty().also { source ->
        source.migrate("surefold/jobs/postgres.sql")
    }
}
