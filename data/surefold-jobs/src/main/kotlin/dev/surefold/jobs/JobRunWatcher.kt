package dev.surefold.jobs

import java.util.ServiceConfigurationError
import java.util.ServiceLoader
import java.util.UUID

/** One run of a job, as a [JobRunWatcher] sees it. [attempt] counts from one. */
class JobRunInfo(val id: UUID, val kind: String, val tenant: String?, val attempt: Int)

/**
 * Wraps every run of a job (spec 0220): a span per run, say. [around] runs inside the context the job's enqueuer
 * carried, calls `block` once, and answers what it answered: the run's result, `done`, `retry`, `continue`,
 * `skipped` or `failed`. Registered through `META-INF/services`, as core's `RetryWatcher` is, or handed to a
 * [JobWorker].
 */
interface JobRunWatcher {
    fun around(run: JobRunInfo, block: () -> String): String
}

internal object JobRunWatchers {
    val registered: List<JobRunWatcher> by lazy {
        try {
            ServiceLoader.load(JobRunWatcher::class.java, JobRunWatcher::class.java.classLoader).toList()
        } catch (failed: ServiceConfigurationError) {
            System.err.println("surefold-jobs: a JobRunWatcher could not be loaded, so none is told: $failed")
            emptyList()
        } catch (failed: LinkageError) {
            System.err.println("surefold-jobs: a JobRunWatcher could not be loaded, so none is told: $failed")
            emptyList()
        }
    }
}
