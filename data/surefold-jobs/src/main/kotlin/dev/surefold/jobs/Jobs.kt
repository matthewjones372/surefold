package dev.surefold.jobs

import dev.surefold.Carriers
import dev.surefold.clock
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.Types
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.UUID

/**
 * Enqueues, cancels and reschedules jobs on the caller's [Connection], so each commits or rolls back with the
 * caller's own transaction. Nothing here commits, and nothing opens a connection. Each is idempotent on a job's key:
 * its tenant, its kind and its dedupe key.
 */
object Jobs {

    /**
     * Enqueues [payload] as a job of [kind], with what this thread carries, its trace among it. Answers false, and
     * changes nothing, when a job with the same key is already waiting or running.
     */
    fun <P> enqueue(
        connection: Connection,
        kind: JobKind<P>,
        payload: P,
        dedupeKey: String,
        runAt: Instant = clock.get().now(),
        expiresAt: Instant? = null,
        priority: Int = 0,
        maxAttempts: Int = 5,
        tenant: String? = null,
    ): Boolean {
        require(maxAttempts >= 1) { "A job needs at least one attempt, not $maxAttempts." }
        val now = clock.get().now()
        val enqueued = connection.prepareStatement(ENQUEUE).use { statement ->
            statement.bind(
                listOf(
                    uuidV7(now), tenant, kind.name, dedupeKey, kind.codec.encode(payload), kind.codec.version,
                    Carried.write(Carriers.capture()), runAt, expiresAt, priority, maxAttempts, now, now,
                ),
            )
            statement.executeUpdate() == 1
        }
        if (enqueued && runAt <= now) notify(connection, kind)
        return enqueued
    }

    // Postgres delivers a notification only when the transaction that sent it commits.
    private fun notify(connection: Connection, kind: JobKind<*>) =
        connection.prepareStatement("select pg_notify('$CHANNEL', ?)").use { statement ->
            statement.bind(listOf(kind.name))
            statement.execute()
        }

    /**
     * Starts a long-running operation: a job of [kind] from [payload], due now, and an audit row saying [startedBy]
     * started it with that payload, and whether as a [dryRun], both in the caller's transaction. Answers the
     * operation's id, which is its job's, for [operation].
     */
    fun <P> startOperation(
        connection: Connection,
        kind: JobKind<P>,
        payload: P,
        startedBy: String,
        dryRun: Boolean = false,
        maxAttempts: Int = 5,
        tenant: String? = null,
    ): UUID {
        val now = clock.get().now()
        val id = uuidV7(now)
        val json = kind.codec.encode(payload)
        connection.prepareStatement(START_OPERATION).use { statement ->
            statement.bind(
                listOf(
                    id, tenant, kind.name, "operation:$id", json, kind.codec.version, Carried.write(Carriers.capture()),
                    now, maxAttempts, dryRun, now, now,
                ),
            )
            statement.executeUpdate()
        }
        connection.prepareStatement(AUDIT_OPERATION).use { statement ->
            statement.bind(listOf(id, tenant, kind.name, startedBy, json, dryRun, now))
            statement.executeUpdate()
        }
        notify(connection, kind)
        return id
    }

    /** What [id], an operation [startOperation] started, has done so far, and who started it; null if no such one. */
    fun operation(connection: Connection, id: UUID): Operation? =
        connection.prepareStatement(OPERATION).use { statement ->
            statement.bind(listOf(id))
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                Operation(
                    id = id,
                    kind = rows.getString("kind"),
                    startedBy = rows.getString("started_by"),
                    parameters = rows.getString("parameters"),
                    dryRun = rows.getBoolean("dry_run"),
                    startedAt = rows.getObject("started_at", OffsetDateTime::class.java).toInstant(),
                    status = rows.getString("status") ?: "pruned",
                    progress = Progress(rows.getLong("progress_done"), rows.getObject("progress_total") as Long?),
                    lastError = rows.getString("last_error"),
                )
            }
        }

    /**
     * Enqueues [payload] as the first occurrence of a series on [rule]: when an occurrence finishes, done, skipped or
     * failed, the next is enqueued with the same key, payload and tenant, in the transaction that finishes it. Cancel
     * by the key ends the series. Answers false, as [enqueue] does, when the key is already waiting or running.
     */
    fun <P> enqueueRecurring(
        connection: Connection,
        kind: JobKind<P>,
        payload: P,
        dedupeKey: String,
        rule: Cron,
        priority: Int = 0,
        maxAttempts: Int = 5,
        tenant: String? = null,
    ): Boolean {
        require(maxAttempts >= 1) { "A job needs at least one attempt, not $maxAttempts." }
        val now = clock.get().now()
        val first = requireNotNull(rule.next(now)) { "The rule $rule never fires." }
        return connection.prepareStatement(ENQUEUE_RECURRING).use { statement ->
            statement.bind(
                listOf(
                    uuidV7(now), tenant, kind.name, dedupeKey, kind.codec.encode(payload), kind.codec.version,
                    Carried.write(Carriers.capture()), first, priority, maxAttempts, rule.stored(), now, now,
                ),
            )
            statement.executeUpdate() == 1
        }
    }

    /** Cancels the waiting job with this key into history. Answers false when none waits: a running one runs on. */
    fun cancel(connection: Connection, kind: JobKind<*>, dedupeKey: String, tenant: String? = null): Boolean {
        val now = clock.get().now()
        return connection.prepareStatement(cancelling(tenant)).use { statement ->
            statement.bind(key(kind, dedupeKey, tenant) + listOf(null, now, now))
            statement.executeUpdate() == 1
        }
    }

    /** Moves the waiting job with this key to [runAt]. Answers false when none is waiting. */
    fun reschedule(
        connection: Connection,
        kind: JobKind<*>,
        dedupeKey: String,
        runAt: Instant,
        tenant: String? = null,
    ): Boolean = connection.prepareStatement(rescheduling(tenant)).use { statement ->
        statement.bind(listOf(runAt, clock.get().now()) + key(kind, dedupeKey, tenant))
        statement.executeUpdate() == 1
    }

    private fun key(kind: JobKind<*>, dedupeKey: String, tenant: String?): List<Any> =
        listOfNotNull(kind.name, dedupeKey, tenant)

    private const val ENQUEUE = """
        insert into surefold_jobs (id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at,
            expires_at, priority, status, max_attempts, created_at, updated_at)
        values (?, ?::text, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?::timestamptz, ?, 'pending', ?, ?, ?)
        on conflict on constraint surefold_jobs_key do nothing
    """

    private const val START_OPERATION = """
        insert into surefold_jobs (id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at,
            priority, status, max_attempts, dry_run, created_at, updated_at)
        values (?, ?::text, ?, ?, ?::jsonb, ?, ?::jsonb, ?, 0, 'pending', ?, ?, ?, ?)
    """

    private const val AUDIT_OPERATION = """
        insert into surefold_jobs_operations (id, tenant_id, kind, started_by, parameters, dry_run, started_at)
        values (?, ?::text, ?, ?, ?::jsonb, ?, ?)
    """

    // The job while it runs, then its history, then nothing once history is pruned: the audit row outlives both.
    private const val OPERATION = """
        select o.kind, o.started_by, o.parameters::text, o.dry_run, o.started_at,
            coalesce(j.status, h.status) as status,
            coalesce(j.progress_done, h.progress_done, 0) as progress_done,
            coalesce(j.progress_total, h.progress_total) as progress_total,
            coalesce(j.last_error, h.last_error) as last_error
        from surefold_jobs_operations o
        left join surefold_jobs j on j.id = o.id
        left join surefold_jobs_history h on h.id = o.id
        where o.id = ?
    """

    private const val ENQUEUE_RECURRING = """
        insert into surefold_jobs (id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at,
            priority, status, max_attempts, recurrence, created_at, updated_at)
        values (?, ?::text, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, 'pending', ?, ?, ?, ?)
        on conflict on constraint surefold_jobs_key do nothing
    """

    // Two forms rather than `is not distinct from`, which no index serves.
    private fun keyed(tenant: String?) =
        "kind = ? and dedupe_key = ? and " + if (tenant == null) "tenant_id is null" else "tenant_id = ?"

    private fun cancelling(tenant: String?) = """
        with gone as (delete from surefold_jobs where status = 'pending' and ${keyed(tenant)} returning *)
        ${historyOf("gone", "cancelled")}
    """

    private fun rescheduling(tenant: String?) =
        "update surefold_jobs set run_at = ?, updated_at = ? where status = 'pending' and ${keyed(tenant)}"
}

/** What an enqueue of a job due now notifies, and what a worker listens on. */
internal const val CHANNEL = "surefold_jobs"

/** The columns a job keeps as they were when it moves to history. */
private const val KEPT = "id, tenant_id, kind, dedupe_key, payload, payload_version, carried, run_at, expires_at, " +
    "priority, attempts, max_attempts, locked_by, created_at, progress_done, progress_total, dry_run"

/**
 * Inserts the rows of [rows], a CTE of deleted jobs, into history as [status]. Takes three parameters: an error that
 * replaces the row's own when not null, then `updated_at` and `finished_at`.
 */
internal fun historyOf(rows: String, status: String) = """
    insert into surefold_jobs_history ($KEPT, last_error, status, updated_at, finished_at)
    select $KEPT, coalesce(?::text, last_error), '$status', ?, ? from $rows
"""

/** Binds [values] in order from the first parameter. A null is sent as text, so the SQL casts what may be null. */
internal fun PreparedStatement.bind(values: List<Any?>) {
    values.forEachIndexed { at, value ->
        when (value) {
            null -> setNull(at + 1, Types.VARCHAR)
            is Instant -> setObject(at + 1, value.atOffset(ZoneOffset.UTC))
            is List<*> -> setArray(at + 1, connection.createArrayOf("text", value.toTypedArray()))
            else -> setObject(at + 1, value)
        }
    }
}

/**
 * A long-running operation as its audit row and its job say: who started it, with what [parameters] (the starting
 * payload's JSON) and whether as a dry run; its job's [status], `pending`, `running`, a finished status, or `pruned`
 * once history has let it go; and its [progress].
 */
data class Operation(
    val id: UUID,
    val kind: String,
    val startedBy: String,
    val parameters: String,
    val dryRun: Boolean,
    val startedAt: Instant,
    val status: String,
    val progress: Progress,
    val lastError: String?,
)
