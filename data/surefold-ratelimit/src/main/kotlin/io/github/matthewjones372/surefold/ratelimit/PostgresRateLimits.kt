package io.github.matthewjones372.surefold.ratelimit

import dev.surefold.clock
import java.sql.Connection
import java.sql.PreparedStatement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import javax.sql.DataSource
import kotlin.math.floor

/**
 * [RateLimits] in one `surefold_rate_limits` table, shared by every instance that reaches [source]. A take refills
 * the key's bucket from the clock and draws on it in one statement on its row, so two instances cannot both spend
 * the last token. The table ships as a Liquibase changelog, `surefold/ratelimit/postgres.sql`.
 */
class PostgresRateLimits(private val source: DataSource) : RateLimits {

    override fun take(key: String, bucket: TokenBucket, cost: Int): Decision {
        require(cost >= 1) { "A take costs at least one token, not $cost." }
        if (cost > bucket.burst) return Decision.Denied(bucket.waitFor(bucket.burst.toDouble(), cost))
        val now = clock.get().now()
        return source.connection.use { connection ->
            val left = connection.prepareStatement(TAKE).use { statement ->
                val burst = bucket.burst.toDouble()
                val refill = listOf<Any>(burst, now, bucket.perSecond)
                statement.bind(listOf<Any>(key, burst - cost, now) + refill + listOf(cost, now) + refill + cost)
                statement.executeQuery().use { rows -> if (rows.next()) rows.getDouble(1) else null }
            }
            if (left != null) Decision.Allowed(floor(left).toLong()) else connection.denied(key, bucket, cost, now)
        }
    }

    /** How long until [key]'s bucket holds [cost], read as it is now. */
    private fun Connection.denied(key: String, bucket: TokenBucket, cost: Int, now: Instant): Decision =
        prepareStatement(HELD).use { statement ->
            statement.bind(listOf(key))
            statement.executeQuery().use { rows ->
                val tokens = if (rows.next()) {
                    bucket.refilled(rows.getDouble(1), rows.getObject(2, OffsetDateTime::class.java).toInstant(), now)
                } else {
                    bucket.burst.toDouble()
                }
                Decision.Denied(bucket.waitFor(tokens, cost))
            }
        }

    private fun PreparedStatement.bind(values: List<Any>) = values.forEachIndexed { at, value ->
        setObject(at + 1, if (value is Instant) value.atOffset(ZoneOffset.UTC) else value)
    }

    private companion object {
        // The refill, as the bucket's own refilled() reckons it: the tokens held, plus the seconds since, at its
        // rate, up to its burst. A clock that went back refills nothing and moves nothing back.
        const val REFILLED = """
            least(?, held.tokens
                + greatest(0, extract(epoch from (?::timestamptz - held.refilled_at)))::double precision * ?)
        """

        const val TAKE = """
            insert into surefold_rate_limits as held (key, tokens, refilled_at) values (?, ?, ?)
            on conflict (key) do update set tokens = $REFILLED - ?,
                refilled_at = greatest(held.refilled_at, ?::timestamptz)
            where $REFILLED >= ?
            returning tokens
        """

        const val HELD = "select tokens, refilled_at from surefold_rate_limits where key = ?"
    }
}
