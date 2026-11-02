package io.github.matthewjones372.surefold.auth

import dev.surefold.clock
import java.sql.ResultSet
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.toJavaDuration

/**
 * [Sessions] in one `surefold_sessions` table, reached through [source]; the table ships as the Liquibase changelog
 * `surefold/auth/postgres.sql`. Every resolve reads the table, so a rotation or a revocation on one instance holds on
 * every other at once. Time is `clock.get()`, so a `TestClock` ages sessions.
 */
class PostgresSessions(
    private val source: DataSource,
    private val settings: SessionSettings = SessionSettings(),
    private val roles: RoleSource = RoleSource.none,
) : Sessions {

    override fun create(user: UserId, tenant: String?): Created {
        val now = clock.get().now()
        val token = Tokens.issue()
        val session = Session(
            id = UUID.randomUUID(),
            user = user,
            tenant = tenant,
            createdAt = now,
            idleUntil = minOf(now + settings.idle.toJavaDuration(), now + settings.absolute.toJavaDuration()),
            absoluteUntil = now + settings.absolute.toJavaDuration(),
        )
        update(
            CREATE, session.id, Tokens.hash(token), user.value, tenant, now, now, session.idleUntil,
            session.absoluteUntil,
        )
        return Created(token, session)
    }

    override fun resolve(token: Secret): Principal? {
        val now = clock.get().now()
        val found = query(FIND, Tokens.hash(token), now, now) { rows ->
            if (!rows.next() || !Tokens.matches(token, rows.getBytes("token_hash"))) return@query null
            Found(
                UserId(rows.getString("user_id")),
                rows.getString("tenant_id"),
                rows.getObject("last_seen_at", OffsetDateTime::class.java).toInstant(),
            )
        } ?: return null
        if (found.lastSeen + settings.slide.toJavaDuration() <= now) {
            update(SLIDE, now, now + settings.idle.toJavaDuration(), Tokens.hash(token), now)
        }
        val grants = roles.grants(found.user, found.tenant)
        return Principal(found.user, found.tenant, grants.roles, grants.permissions)
    }

    override fun rotate(token: Secret): Secret? {
        val now = clock.get().now()
        val next = Tokens.issue()
        return next.takeIf { update(ROTATE, Tokens.hash(next), Tokens.hash(token), now, now) == 1 }
    }

    override fun revoke(token: Secret): Boolean = update(REVOKE, Tokens.hash(token)) == 1

    override fun revokeAll(user: UserId): Int = update(REVOKE_ALL, user.value)

    /** Deletes every session past either expiry; one a resolve would refuse anyway. */
    fun prune(): Int {
        val now = clock.get().now()
        return update(PRUNE, now, now)
    }

    private class Found(val user: UserId, val tenant: String?, val lastSeen: Instant)

    private fun update(sql: String, vararg values: Any?): Int = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.bind(*values)
            statement.executeUpdate()
        }
    }

    private fun <T> query(sql: String, vararg values: Any?, read: (ResultSet) -> T): T =
        source.connection.use { connection ->
            connection.prepareStatement(sql).use { statement ->
                statement.bind(*values)
                statement.executeQuery().use(read)
            }
        }

    private companion object {
        const val CREATE = """
            insert into surefold_sessions
                (id, token_hash, user_id, tenant_id, created_at, last_seen_at, idle_until, absolute_until)
            values (?, ?, ?, ?, ?, ?, ?, ?)
        """

        const val FIND = """
            select token_hash, user_id, tenant_id, last_seen_at from surefold_sessions
            where token_hash = ? and idle_until > ? and absolute_until > ?
        """

        // The idle expiry never passes the absolute one.
        const val SLIDE = """
            update surefold_sessions set last_seen_at = ?, idle_until = least(?, absolute_until)
            where token_hash = ? and idle_until > ?
        """

        const val ROTATE = """
            update surefold_sessions set token_hash = ?
            where token_hash = ? and idle_until > ? and absolute_until > ?
        """

        const val REVOKE = "delete from surefold_sessions where token_hash = ?"

        const val REVOKE_ALL = "delete from surefold_sessions where user_id = ?"

        const val PRUNE = "delete from surefold_sessions where idle_until <= ? or absolute_until <= ?"
    }
}
