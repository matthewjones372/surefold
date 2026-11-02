package dev.surefold.app.jobs

import dev.surefold.jobs.CronEntry
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.closingOf
import javax.sql.DataSource

/**
 * A [JobWorker] running [handlers], and enqueueing the firings of [cron], as a node. It takes a [Migrated], so it
 * starts only once the migrations that hold the jobs tables have run, and its [JobSettings] from the graph. Released,
 * it stops claiming and drains.
 */
fun jobWorker(handlers: JobHandlers, cron: List<CronEntry<*>> = emptyList()): Module =
    closingOf { source: DataSource, _: Migrated, settings: JobSettings ->
        JobWorker(source, handlers, settings, cron = cron).start()
    }
