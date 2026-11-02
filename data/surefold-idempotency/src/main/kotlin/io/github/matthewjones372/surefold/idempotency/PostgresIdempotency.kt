package io.github.matthewjones372.surefold.idempotency

import dev.surefold.ByteChunk
import dev.surefold.clock
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * An [IdempotencyStore] in one `surefold_idempotency` table, reached through [source]. The table ships as a Liquibase
 * changelog, `surefold/idempotency/postgres.sql`. A reservation holds its key for [lease]: an instance that dies with
 * a request running leaves a key the next retry can reserve once the lease passes.
 */
class PostgresIdempotency(private val source: DataSource, private val lease: Duration = 1.minutes) : IdempotencyStore {

    override fun reserve(key: IdempotencyKey, fingerprint: String, ttl: Duration): Reservation {
        val now = clock.get().now()
        val token = UUID.randomUUID()
        return source.connection.use { connection ->
            val reserved = connection.prepareStatement(RESERVE).use { statement ->
                statement.bind(
                    key.scope, key.key, fingerprint, token, now + lease.toJavaDuration(), now + ttl.toJavaDuration(),
                    now, now, now,
                )
                statement.executeQuery().use { it.next() }
            }
            if (reserved) Reservation.Reserved(token) else connection.found(key, fingerprint)
        }
    }

    /** What is held at [key], which an expired or abandoned row would have let [reserve] take. */
    private fun Connection.found(key: IdempotencyKey, fingerprint: String): Reservation =
        prepareStatement(FOUND).use { statement ->
            statement.bind(key.scope, key.key)
            statement.executeQuery().use { rows ->
                when {
                    // Pruned between the two statements: as good as in progress, and a retry reserves it.
                    !rows.next() -> Reservation.InProgress

                    rows.getString("fingerprint") != fingerprint -> Reservation.Mismatch

                    rows.getString("status") == "done" -> Reservation.Replay(rows.response())

                    else -> Reservation.InProgress
                }
            }
        }

    override fun complete(key: IdempotencyKey, token: UUID, response: StoredResponse): Boolean =
        source.connection.use { connection ->
            connection.prepareStatement(COMPLETE).use { statement ->
                statement.bind(
                    response.status,
                    connection.createArrayOf("text", response.headers.map { it.first }.toTypedArray()),
                    connection.createArrayOf("text", response.headers.map { it.second }.toTypedArray()),
                    response.body.toByteArray(),
                    key.scope,
                    key.key,
                    token,
                )
                statement.executeUpdate() == 1
            }
        }

    override fun release(key: IdempotencyKey, token: UUID): Boolean = source.connection.use { connection ->
        connection.prepareStatement(RELEASE).use { statement ->
            statement.bind(key.scope, key.key, token)
            statement.executeUpdate() == 1
        }
    }

    /** Deletes every key past its expiry, except one whose request is still running on a live lease. */
    fun prune(): Int = source.connection.use { connection ->
        val now = clock.get().now()
        connection.prepareStatement(PRUNE).use { statement ->
            statement.bind(now, now)
            statement.executeUpdate()
        }
    }

    private fun ResultSet.response(): StoredResponse {
        val names = getArray("header_names").array as Array<*>
        val values = getArray("header_values").array as Array<*>
        return StoredResponse(
            getInt("response_status"),
            names.zip(values) { name, value -> "$name" to "$value" },
            ByteChunk.from(getBytes("body")),
        )
    }

    private fun PreparedStatement.bind(vararg values: Any) = values.forEachIndexed { at, value ->
        setObject(at + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
    }

    private companion object {
        // Only an expired key, or a reservation whose lease ran out, lets the insert through as an update.
        const val RESERVE = """
            insert into surefold_idempotency as held
                (scope, key, fingerprint, status, holder, locked_until, expires_at, created_at)
            values (?, ?, ?, 'running', ?, ?, ?, ?)
            on conflict (scope, key) do update set fingerprint = excluded.fingerprint, status = 'running',
                holder = excluded.holder, locked_until = excluded.locked_until, expires_at = excluded.expires_at,
                created_at = excluded.created_at, response_status = null, header_names = null,
                header_values = null, body = null
            where held.expires_at <= ? or (held.status = 'running' and held.locked_until <= ?)
            returning 1
        """

        const val FOUND = """
            select fingerprint, status, response_status, header_names, header_values, body
            from surefold_idempotency where scope = ? and key = ?
        """

        const val COMPLETE = """
            update surefold_idempotency set status = 'done', locked_until = null, response_status = ?,
                header_names = ?, header_values = ?, body = ?
            where scope = ? and key = ? and holder = ? and status = 'running'
        """

        const val PRUNE = """
            delete from surefold_idempotency
            where expires_at <= ? and (status = 'done' or locked_until <= ?)
        """

        const val RELEASE =
            "delete from surefold_idempotency where scope = ? and key = ? and holder = ? and status = 'running'"
    }
}
