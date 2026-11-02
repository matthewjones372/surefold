package io.github.matthewjones372.surefold.jobs

import dev.surefold.clock
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.toJavaDuration
import kotlin.time.toKotlinDuration

/** A job one worker holds the lease on, as claimed. [attempts] counts this run, and fences what the run writes. */
internal data class Claimed(
    val id: UUID,
    val tenant: String?,
    val kind: String,
    val dedupeKey: String,
    val payload: String,
    val payloadVersion: Int,
    val carried: String?,
    val runAt: Instant,
    val expiresAt: Instant?,
    val priority: Int,
    val attempts: Int,
    val maxAttempts: Int,
    val recurrence: Cron? = null,
    val progress: Progress = Progress(),
    val dryRun: Boolean = false,
)

/** How many jobs of a kind are due and waiting, and how long the oldest has waited past its run time. */
internal data class Backlog(val depth: Long, val lag: Duration)

/** Which kinds a claim takes. */
internal sealed interface Kinds {
    data object All : Kinds

    data class Only(val kind: JobKind<*>) : Kinds

    /** Every kind but these: the ones no handler here runs. */
    data class Except(val names: Set<String>) : Kinds
}

/** How a run left its job, as history records it. */
internal enum class Finished(val status: String) { Done("done"), Failed("failed"), Skipped("skipped") }

/**
 * The jobs table as [worker] sees it: what it claims, finishes and sweeps, each in a transaction of its own on
 * [source]. A finish names the attempt it ran, so a worker whose lease expired and was claimed again changes nothing.
 */
internal class Queue(private val source: DataSource, private val worker: String) {

    /**
     * Claims up to [limit] due jobs, highest priority and then earliest first, and holds each for [lease]. Rows
     * another worker is claiming are skipped rather than waited on.
     */
    fun claim(limit: Int, lease: Duration, kinds: Kinds = Kinds.All, perTenant: Int? = null): List<Claimed> {
        val rate = (kinds as? Kinds.Only)?.kind?.rate
        return source.connection.use { connection ->
            if (rate == null) {
                claimOn(connection, limit, lease, kinds, perTenant)
            } else {
                connection.autoCommit = false
                try {
                    val kind = kinds.kind.name
                    val bucket = connection.bucket(kind, rate)
                    val claimed = if (bucket.whole() == 0) {
                        emptyList()
                    } else {
                        claimOn(connection, minOf(limit, bucket.whole()), lease, kinds, perTenant)
                    }
                    connection.spend(kind, bucket.tokens - claimed.size)
                    connection.commit()
                    claimed
                } finally {
                    connection.rollbackIfOpen()
                }
            }
        }.sortedWith(compareByDescending<Claimed> { it.priority }.thenBy { it.runAt })
    }

    private fun claimOn(
        connection: Connection,
        limit: Int,
        lease: Duration,
        kinds: Kinds,
        perTenant: Int?,
    ): List<Claimed> {
        val now = clock.get().now()
        val (filter, names) = when (kinds) {
            Kinds.All -> "" to emptyList()
            is Kinds.Only -> "and kind = ?" to listOf(kinds.kind.name)
            is Kinds.Except -> "and kind <> all(?)" to listOf(kinds.names.toList())
        }
        val sql = if (perTenant == null) claiming(filter) else claimingFairly(filter)
        return connection.prepareStatement(sql).use { statement ->
            val bounds = if (perTenant == null) listOf(limit) else listOf(limit * CANDIDATES, perTenant, limit)
            statement.bind(listOf(now + lease.toJavaDuration(), worker, now, now) + names + bounds)
            statement.executeQuery().use { rows ->
                generateSequence { if (rows.next()) rows.claimed() else null }.toList()
            }
        }
    }

    /** What is left in [kind]'s bucket now, refilled from the clock: made full on first use, and locked till commit. */
    private fun Connection.bucket(kind: String, rate: JobRate): Bucket {
        val now = clock.get().now()
        prepareStatement(BUCKET_MADE).use { statement ->
            statement.bind(listOf(kind, rate.burst.toDouble(), now))
            statement.executeUpdate()
        }
        return prepareStatement(BUCKET_HELD).use { statement ->
            statement.bind(listOf(kind))
            statement.executeQuery().use { rows ->
                rows.next()
                val held = rows.getDouble(1)
                val since = rows.getObject(2, OffsetDateTime::class.java).toInstant()
                val elapsed = java.time.Duration.between(since, now).toKotlinDuration().coerceAtLeast(Duration.ZERO)
                Bucket(minOf(rate.burst.toDouble(), held + elapsed / (rate.per / rate.rate)))
            }
        }
    }

    private fun Connection.spend(kind: String, left: Double) = prepareStatement(BUCKET_SPENT).use { statement ->
        statement.bind(listOf(left, clock.get().now(), kind))
        statement.executeUpdate()
    }

    private fun Connection.rollbackIfOpen() {
        if (!autoCommit) {
            rollback()
            autoCommit = true
        }
    }

    private class Bucket(val tokens: Double) {
        fun whole(): Int = floor(tokens).toInt()
    }

    /** Moves [job] to history as [how], with [error] if given. Answers false when this worker no longer holds it. */
    fun finish(job: Claimed, how: Finished, error: String? = null): Boolean {
        val now = clock.get().now()
        return source.connection.use { connection ->
            val rule = job.recurrence
            if (rule != null) connection.autoCommit = false
            try {
                val finished = connection.prepareStatement(finishing(how)).use { statement ->
                    statement.bind(listOf(job.id, job.attempts, worker, error, now, now))
                    statement.executeUpdate() == 1
                }
                if (rule != null) {
                    if (finished) connection.enqueueNext(job, rule)
                    connection.commit()
                }
                finished
            } finally {
                connection.rollbackIfOpen()
            }
        }
    }

    /** The occurrence after [job]'s, with its key, payload and tenant, never earlier than now: no catching up. */
    private fun Connection.enqueueNext(job: Claimed, rule: Cron) {
        val now = clock.get().now()
        val at = rule.next(maxOf(job.runAt, now)) ?: return
        prepareStatement(NEXT).use { statement ->
            statement.bind(
                listOf(
                    uuidV7(now), job.tenant, job.kind, job.dedupeKey, job.payload, job.payloadVersion, job.carried,
                    at, job.priority, job.maxAttempts, rule.stored(), now, now,
                ),
            )
            statement.executeUpdate()
        }
    }

    /**
     * Returns [job] to pending, to run at [at] with [error] noted. A retry the handler did not [count] gives its
     * attempt back. Answers false when this worker no longer holds it.
     */
    fun retry(job: Claimed, at: Instant, error: String, count: Boolean): Boolean {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(RETRY).use { statement ->
                statement.bind(listOf(at, error, if (count) 0 else 1, now, job.id, job.attempts, worker))
                statement.executeUpdate() == 1
            }
        }
    }

    /**
     * Returns [job] to pending at [at] with [payload] in place of its own and [progress] added to its counters, giving
     * its attempt back. Answers false when this worker no longer holds it.
     */
    fun proceed(job: Claimed, payload: String, at: Instant, progress: Progress): Boolean {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(PROCEED).use { statement ->
                statement.bind(listOf(payload, at, progress.done, progress.total, now, job.id, job.attempts, worker))
                statement.executeUpdate() == 1
            }
        }
    }

    /**
     * Returns each running job whose lease has passed to pending, as a worker that died mid-run left it, and fails into
     * history those that have had every attempt. Answers how many it returned.
     */
    fun sweep(): Int {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(sweeping).use { statement ->
                statement.bind(listOf(now, "its lease expired on its last attempt", now, now, now))
                statement.executeUpdate()
            }
        }
    }

    /** For each kind with a job due and waiting: how many, and how long the longest has waited past its run time. */
    fun backlog(): Map<String, Backlog> {
        val now = clock.get().now()
        return source.connection.use { connection ->
            connection.prepareStatement(BACKLOG).use { statement ->
                statement.bind(listOf(now))
                statement.executeQuery().use { rows ->
                    generateSequence {
                        if (!rows.next()) return@generateSequence null
                        val oldest = rows.getObject(3, OffsetDateTime::class.java).toInstant()
                        rows.getString(1) to
                            Backlog(rows.getLong(2), java.time.Duration.between(oldest, now).toKotlinDuration())
                    }.toMap()
                }
            }
        }
    }

    /** Deletes history that finished more than [retention] ago, and answers how many rows. */
    fun prune(retention: Duration): Int = source.connection.use { connection ->
        connection.prepareStatement("delete from surefold_jobs_history where finished_at < ?").use { statement ->
            statement.bind(listOf(clock.get().now() - retention.toJavaDuration()))
            statement.executeUpdate()
        }
    }

    private fun ResultSet.claimed() = Claimed(
        id = getObject("id", UUID::class.java),
        tenant = getString("tenant_id"),
        kind = getString("kind"),
        dedupeKey = getString("dedupe_key"),
        payload = getString("payload"),
        payloadVersion = getInt("payload_version"),
        carried = getString("carried"),
        runAt = getObject("run_at", OffsetDateTime::class.java).toInstant(),
        expiresAt = instant("expires_at"),
        priority = getInt("priority"),
        attempts = getInt("attempts"),
        maxAttempts = getInt("max_attempts"),
        recurrence = getString("recurrence")?.let(Cron::read),
        progress = Progress(getLong("progress_done"), getObject("progress_total") as Long?),
        dryRun = getBoolean("dry_run"),
    )

    private fun ResultSet.instant(column: String): Instant? = getObject(column, OffsetDateTime::class.java)?.toInstant()

    private companion object {
        fun claiming(filter: String) = """
            update surefold_jobs
            set status = 'running', locked_until = ?, locked_by = ?, attempts = attempts + 1, updated_at = ?
            where id in (
                select id from surefold_jobs where status = 'pending' and run_at <= ? $filter
                order by priority desc, run_at limit ? for update skip locked
            )
            returning id, tenant_id, kind, dedupe_key, payload::text, payload_version, carried::text, run_at,
                expires_at, priority, attempts, max_attempts, recurrence, progress_done, progress_total,
                dry_run
        """

        /** How many due jobs a fair claim ranks, per job it may take: a backlog of millions costs a bounded scan. */
        const val CANDIDATES = 20

        // At most perTenant from each tenant, ranked within it as a plain claim orders; no tenant is one tenant.
        fun claimingFairly(filter: String) = """
            update surefold_jobs
            set status = 'running', locked_until = ?, locked_by = ?, attempts = attempts + 1, updated_at = ?
            where id in (
                select id from surefold_jobs where id in (
                    select id from (
                        select id, priority, run_at,
                            row_number() over (partition by tenant_id order by priority desc, run_at) as place
                        from (
                            select id, tenant_id, priority, run_at from surefold_jobs
                            where status = 'pending' and run_at <= ? $filter
                            order by priority desc, run_at limit ?
                        ) candidates
                    ) ranked
                    where place <= ? order by priority desc, run_at limit ?
                ) and status = 'pending' for update skip locked
            )
            returning id, tenant_id, kind, dedupe_key, payload::text, payload_version, carried::text, run_at,
                expires_at, priority, attempts, max_attempts, recurrence, progress_done, progress_total,
                dry_run
        """

        const val PROCEED = """
            update surefold_jobs set status = 'pending', payload = ?::jsonb, run_at = ?, attempts = attempts - 1,
                progress_done = progress_done + ?, progress_total = coalesce(?::bigint, progress_total),
                last_error = null, locked_until = null, locked_by = null, updated_at = ?
            where id = ? and attempts = ? and locked_by = ? and status = 'running'
        """

        const val NEXT = """
            insert into surefold_jobs (id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at,
                priority, status, max_attempts, recurrence, created_at, updated_at)
            values (?, ?::text, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, 'pending', ?, ?, ?, ?)
            on conflict on constraint surefold_jobs_key do nothing
        """

        const val BUCKET_MADE = """
            insert into surefold_jobs_buckets (kind, tokens, refilled_at) values (?, ?, ?) on conflict (kind) do nothing
        """

        const val BUCKET_HELD = "select tokens, refilled_at from surefold_jobs_buckets where kind = ? for update"

        const val BUCKET_SPENT = "update surefold_jobs_buckets set tokens = ?, refilled_at = ? where kind = ?"

        const val BACKLOG = """
            select kind, count(*), min(run_at) from surefold_jobs where status = 'pending' and run_at <= ? group by kind
        """

        const val RETRY = """
            update surefold_jobs set status = 'pending', run_at = ?, last_error = ?, attempts = attempts - ?,
                locked_until = null, locked_by = null, updated_at = ?
            where id = ? and attempts = ? and locked_by = ? and status = 'running'
        """

        fun finishing(how: Finished) = """
            with gone as (
                delete from surefold_jobs where id = ? and attempts = ? and locked_by = ? and status = 'running'
                returning *
            )
            ${historyOf("gone", how.status)}
        """

        // One statement, so a job is either failed or returned, never both and never neither.
        val sweeping = """
            with expired as (
                select id from surefold_jobs where status = 'running' and locked_until < ? for update skip locked
            ),
            spent as (
                delete from surefold_jobs where id in (select id from expired) and attempts >= max_attempts
                returning *
            ),
            failed as (${historyOf("spent", Finished.Failed.status)} returning 1)
            update surefold_jobs set status = 'pending', locked_until = null, locked_by = null,
                last_error = 'its lease expired', updated_at = ?
            where id in (select id from expired) and attempts < max_attempts
        """
    }
}
