package dev.surefold.app.jobs

import dev.surefold.app.Migrated
import dev.surefold.app.Module
import dev.surefold.app.closingOf
import dev.surefold.jobs.CronEntry
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
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
