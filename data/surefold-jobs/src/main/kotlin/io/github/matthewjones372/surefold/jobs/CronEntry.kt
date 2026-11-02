package io.github.matthewjones372.surefold.jobs

import dev.surefold.clock
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.toJavaDuration

/**
 * A job the service runs on a timetable: [kind] with [payload], at every firing of [cron]. Every worker enqueues the
 * firings due before its next housekeeping, each with the dedupe key `cron:<name>:<instant>`, so any number of
 * instances enqueue each firing once. A firing missed while no worker ran is not caught up.
 */
class CronEntry<P>(val name: String, val cron: Cron, val kind: JobKind<P>, val payload: P) {
    init {
        require(name.isNotBlank()) { "A cron entry needs a name." }
    }

    /** Enqueues each firing after now and up to [ahead] from now, and answers how many were new. */
    internal fun enqueueAhead(source: DataSource, ahead: Duration): Int {
        val now = clock.get().now()
        val until = now + ahead.toJavaDuration()
        val firings = generateSequence(cron.next(now)) { cron.next(it) }
            .takeWhile { !it.isAfter(until) }
            .take(MOST_AHEAD)
            .toList()
        if (firings.isEmpty()) return 0
        return source.connection.use { connection ->
            firings.count { at -> Jobs.enqueue(connection, kind, payload, key(at), runAt = at) }
        }
    }

    private fun key(at: Instant) = "cron:$name:$at"

    override fun toString(): String = "CronEntry($name, $cron)"

    private companion object {
        // A cron that fires every minute, ahead by an hour's housekeeping, is sixty; this bounds a mistake.
        const val MOST_AHEAD = 1_000
    }
}
