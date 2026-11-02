package dev.surefold.auth

import dev.surefold.clock
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.toJavaDuration

/**
 * Single-use sign-in links in one `surefold_magic_links` table, from the changelog `surefold/auth/postgres.sql`. Each
 * lives [ttl] and is stored, as a session's token is, only as its hash. The service sends the token; this issues and
 * redeems it.
 */
class PostgresMagicLinks(private val source: DataSource, private val ttl: Duration = 15.minutes) {

    /** A token to put in an emailed URL, good once within [ttl]. */
    fun issue(user: UserId): Secret {
        val now = clock.get().now()
        val token = Tokens.issue()
        source.connection.use { connection ->
            connection.prepareStatement(ISSUE).use { statement ->
                statement.bind(Tokens.hash(token), user.value, now + ttl.toJavaDuration())
                statement.executeUpdate()
            }
        }
        return token
    }

    /**
     * The user [token] was issued to, or null once it has been redeemed, expired, or was never issued. The row is
     * deleted by the statement that reads it, so of any number redeeming one link at once, one gets it.
     */
    fun redeem(token: Secret): UserId? = source.connection.use { connection ->
        connection.prepareStatement(REDEEM).use { statement ->
            statement.bind(Tokens.hash(token), clock.get().now())
            statement.executeQuery().use { rows ->
                if (rows.next() && Tokens.matches(token, rows.getBytes(2))) UserId(rows.getString(1)) else null
            }
        }
    }

    /** Deletes every link past its expiry, unredeemed. */
    fun prune(): Int = source.connection.use { connection ->
        connection.prepareStatement(PRUNE).use { statement ->
            statement.bind(clock.get().now())
            statement.executeUpdate()
        }
    }

    private companion object {
        const val ISSUE = "insert into surefold_magic_links (token_hash, user_id, expires_at) values (?, ?, ?)"

        // An expired link is deleted too, without answering: it is no good to anyone.
        const val REDEEM = """
            with taken as (
                delete from surefold_magic_links where token_hash = ? returning user_id, token_hash, expires_at
            )
            select user_id, token_hash from taken where expires_at > ?
        """

        const val PRUNE = "delete from surefold_magic_links where expires_at <= ?"
    }
}
