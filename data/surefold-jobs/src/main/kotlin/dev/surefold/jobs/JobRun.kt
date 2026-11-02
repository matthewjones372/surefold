package dev.surefold.jobs

import dev.surefold.Carriers
import dev.surefold.Schedule
import dev.surefold.clock
import dev.surefold.counter
import dev.surefold.fail.nonFatalOrThrow
import dev.surefold.increment
import dev.surefold.logWarn
import dev.surefold.timed
import dev.surefold.timeoutOrNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toJavaDuration

/** Runs, tagged by kind and by result: `done`, `retry`, `continue`, `skipped` or `failed`. */
internal const val RUNS = "surefold.jobs.runs"

/** How long each handler ran, in seconds, tagged by kind. */
internal const val DURATION = "surefold.jobs.duration.seconds"

/** Doubling from a second, each delay somewhere between half and one and a half times as long. */
internal val defaultBackoff: Schedule<Unit, Any?> = Schedule.exponential<Unit>(1.seconds).jittered(0.5, 1.5)

/**
 * Runs claimed jobs through [handlers] and writes what each decided back through [queue]. A job past its expiry is
 * skipped without a run. One of a kind nothing handles, or with a payload its codec cannot read, fails at once. A
 * run that throws or misses its deadline is retried after [backoff]'s delay for its attempt, and one that answers
 * Retry after the delay it asks for; either fails on its last attempt.
 */
internal class JobRun(
    private val queue: Queue,
    private val handlers: JobHandlers,
    private val backoff: Schedule<Unit, Any?> = defaultBackoff,
    private val watchers: List<JobRunWatcher> = JobRunWatchers.registered,
) {

    /**
     * Runs [claimed] under the context its enqueuer carried, inside every watcher, and counts what came of it. The
     * watchers run inside that context, so a span one starts can link to the enqueuer's.
     */
    fun run(claimed: Claimed) {
        val info = JobRunInfo(claimed.id, claimed.kind, claimed.tenant, claimed.attempts)
        val outcome = Carriers.within(carried(claimed)) { watched(info, watchers) { outcome(claimed) } }
        counter(RUNS, "kind" to claimed.kind, "result" to outcome).increment()
    }

    private fun watched(info: JobRunInfo, left: List<JobRunWatcher>, block: () -> String): String =
        if (left.isEmpty()) block() else left.first().around(info) { watched(info, left.drop(1), block) }

    private fun outcome(claimed: Claimed): String {
        val now = clock.get().now()
        val handled = handlers.byName[claimed.kind]
        return when {
            claimed.expiresAt != null && now > claimed.expiresAt -> {
                queue.finish(claimed, Finished.Skipped, "expired at ${claimed.expiresAt}")
                "skipped"
            }

            handled == null -> failed(claimed, "no handler for kind \"${claimed.kind}\"")

            else -> timed(DURATION, "kind" to claimed.kind) { outcome(claimed, handled) }
        }
    }

    private fun <P> outcome(claimed: Claimed, handled: Handled<P>): String {
        val job = try {
            decoded(claimed, handled.kind)
        } catch (thrown: Exception) {
            return failed(claimed, "its payload could not be read: ${thrown.nonFatalOrThrow()}")
        }
        val result = try {
            timeoutOrNull(handled.kind.deadline) { handled.handler.run(job) }
                ?: return retried(claimed, "no answer within ${handled.kind.deadline}")
        } catch (thrown: Exception) {
            return retried(claimed, "it threw ${thrown.nonFatalOrThrow()}")
        }
        return when (result) {
            JobResult.Done -> "done".also { queue.finish(claimed, Finished.Done) }

            is JobResult.Skip -> "skipped".also { queue.finish(claimed, Finished.Skipped, result.reason) }

            is JobResult.Fail -> failed(claimed, result.reason)

            // A handler that answers Retry for ever is failed once its attempts are spent, as a throwing one is.
            is JobResult.Retry -> when {
                claimed.attempts < claimed.maxAttempts -> "retry".also {
                    queue.retry(claimed, clock.get().now() + result.after.toJavaDuration(), result.reason, count = true)
                }

                else -> failed(claimed, "${result.reason}, on attempt ${claimed.attempts} of ${claimed.maxAttempts}")
            }

            is JobResult.Continue<*> -> "continue".also {
                // A handler of P answers a Continue of P: the handler's type says so, and erasure cannot.
                @Suppress("UNCHECKED_CAST")
                val next = handled.kind.codec.encode(result.payload as P)
                queue.proceed(claimed, next, clock.get().now() + result.after.toJavaDuration(), result.progress)
            }
        }
    }

    /** What the enqueuer carried; a row whose context cannot be read runs without it rather than not at all. */
    private fun carried(claimed: Claimed): Map<String, String> = try {
        Carried.read(claimed.carried)
    } catch (unread: IllegalArgumentException) {
        logWarn("surefold-jobs: job ${claimed.id} runs without its enqueuer's context: ${unread.message}")
        emptyMap()
    }

    private fun <P> decoded(claimed: Claimed, kind: JobKind<P>) = Job(
        id = claimed.id,
        kind = kind,
        payload = kind.codec.decode(claimed.payloadVersion, claimed.payload),
        tenant = claimed.tenant,
        runAt = claimed.runAt,
        attempt = claimed.attempts,
        progress = claimed.progress,
        dryRun = claimed.dryRun,
    )

    private fun retried(claimed: Claimed, error: String): String {
        val delay = if (claimed.attempts < claimed.maxAttempts) delayAfter(claimed.attempts) else null
        if (delay == null) return failed(claimed, "$error, on attempt ${claimed.attempts} of ${claimed.maxAttempts}")
        queue.retry(claimed, clock.get().now() + delay.toJavaDuration(), error, count = true)
        return "retry"
    }

    private fun failed(claimed: Claimed, error: String): String {
        logWarn("surefold-jobs: job ${claimed.id} of kind \"${claimed.kind}\" failed: $error")
        queue.finish(claimed, Finished.Failed, error)
        return "failed"
    }

    /** [backoff]'s delay before the attempt after [attempt], or null when it has given up. */
    private fun delayAfter(attempt: Int): Duration? {
        var step = backoff.step
        repeat(attempt - 1) {
            step = (step(Unit) as? Schedule.Decision.Continue)?.step ?: return null
        }
        return (step(Unit) as? Schedule.Decision.Continue)?.delay
    }
}
