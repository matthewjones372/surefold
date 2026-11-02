package dev.surefold.jobs

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration

/** What a run of a job decided. */
sealed interface JobResult {
    /** The job is finished. */
    data object Done : JobResult

    /** Run it again after [after]. It uses up an attempt, so a job that never stops asking fails on its last. */
    data class Retry(val after: Duration, val reason: String) : JobResult

    /** The job no longer needs doing. */
    data class Skip(val reason: String) : JobResult

    /** The job cannot be done, and is not tried again. */
    data class Fail(val reason: String) : JobResult

    /**
     * More of the job is left: store [payload], a cursor saying where it got to, add [progress] to its counters, and
     * run it again after [after]. Unlike [Retry], it uses no attempt: it is progress. A crash after this resumes from
     * [payload].
     */
    data class Continue<P>(val payload: P, val after: Duration = Duration.ZERO, val progress: Progress = Progress()) :
        JobResult
}

/** How far a job has got: [done] is added up across its runs, and [total], when a run knows it, is kept. */
data class Progress(val done: Long = 0, val total: Long? = null)

/**
 * One run of a job, as its handler sees it. [attempt] counts from one; [progress] is what earlier runs added up; and
 * [dryRun] says the operation was started to show what it would do, so the handler must change nothing.
 */
class Job<P>(
    val id: UUID,
    val kind: JobKind<P>,
    val payload: P,
    val tenant: String?,
    val runAt: Instant,
    val attempt: Int,
    val progress: Progress = Progress(),
    val dryRun: Boolean = false,
)

/** Runs one job of a kind. Delivery is at least once: a job can run again after a crash, so this must be idempotent. */
fun interface JobHandler<P> {
    fun run(job: Job<P>): JobResult
}

/** A [JobKind] with the handler that runs it. */
class Handled<P> internal constructor(val kind: JobKind<P>, internal val handler: JobHandler<P>)

/** The handlers a worker runs, at most one per kind. */
class JobHandlers private constructor(internal val byName: Map<String, Handled<*>>) {
    companion object {
        fun of(vararg handled: Handled<*>): JobHandlers {
            val twice = handled.groupBy { it.kind.name }.filterValues { it.size > 1 }.keys
            require(twice.isEmpty()) { "Each job kind takes one handler, but these have more: $twice" }
            return JobHandlers(handled.associateBy { it.kind.name })
        }
    }
}
