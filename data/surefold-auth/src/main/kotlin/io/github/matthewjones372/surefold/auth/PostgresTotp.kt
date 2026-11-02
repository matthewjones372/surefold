package io.github.matthewjones372.surefold.auth

import dev.surefold.clock
import java.net.URLEncoder
import java.security.SecureRandom
import javax.sql.DataSource

/** A secret to enrol in an authenticator app, as its base32 [secret] and an `otpauth://` [uri] for a QR code. */
data class Enrolment(val secret: Secret, val uri: Secret)

/**
 * TOTP second factors in one `surefold_totp` table, from the changelog `surefold/auth/postgres.sql`. Each secret is
 * encrypted under [key] and bound to its user, so a database dump alone gives no one a second factor, nor can a row
 * be moved to another user. A code is good within one 30-second step either side of now, and once: the step it
 * matched is stored, and that step and every earlier one are refused after.
 */
class PostgresTotp(private val source: DataSource, private val key: AesKey) {
    private val random = SecureRandom()

    /** A new secret for [user], replacing any they had; it verifies nothing until [confirm] sees its first code. */
    fun enrol(user: UserId, issuer: String): Enrolment {
        val secret = ByteArray(SECRET_BYTES).also(random::nextBytes)
        update(ENROL, user.value, key.encrypt(secret, user.value))
        val encoded = Base32.encode(secret)
        val label = "${encode(issuer)}:${encode(user.value)}"
        return Enrolment(
            Secret(encoded),
            Secret("otpauth://totp/$label?secret=$encoded&issuer=${encode(issuer)}&algorithm=SHA1&digits=6&period=30"),
        )
    }

    /** Switches on [user]'s enrolment when [code] is right; false when it is wrong, or there is nothing to confirm. */
    fun confirm(user: UserId, code: String): Boolean = check(user, code, confirmed = false)

    /** Whether [code] is [user]'s, from a confirmed enrolment, and not used before. */
    fun verify(user: UserId, code: String): Boolean = check(user, code, confirmed = true)

    /** Whether [user] has a confirmed second factor. */
    fun enrolled(user: UserId): Boolean = query(FIND, user.value)?.confirmed == true

    fun remove(user: UserId): Boolean = update(REMOVE, user.value) == 1

    private fun check(user: UserId, code: String, confirmed: Boolean): Boolean {
        val row = query(FIND, user.value)?.takeIf { it.confirmed == confirmed } ?: return false
        val secret = key.decrypt(row.secret, user.value)
        val now = Totp.step(clock.get().now())
        // Every step in the window is computed and compared, so how long this takes says nothing of which matched.
        val matched = (now - 1..now + 1)
            .filter { step -> constantTimeEquals(Totp.code(secret, step), code) }
            .lastOrNull { step -> row.lastStep == null || step > row.lastStep }
            ?: return false
        return update(USE, matched, user.value, confirmed, matched) == 1
    }

    private class Row(val secret: ByteArray, val confirmed: Boolean, val lastStep: Long?)

    private fun query(sql: String, user: String): Row? = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.bind(user)
            statement.executeQuery().use { rows ->
                if (!rows.next()) return@use null
                Row(
                    rows.getBytes("secret"), rows.getBoolean("confirmed"),
                    rows.getLong("last_step").takeIf {
                        !rows.wasNull()
                    },
                )
            }
        }
    }

    private fun update(sql: String, vararg values: Any?): Int = source.connection.use { connection ->
        connection.prepareStatement(sql).use { statement ->
            statement.bind(*values)
            statement.executeUpdate()
        }
    }

    private fun encode(text: String) = URLEncoder.encode(text, Charsets.UTF_8).replace("+", "%20")

    private companion object {
        // 160 bits, as RFC 4226 recommends for HMAC-SHA1.
        const val SECRET_BYTES = 20

        const val ENROL = """
            insert into surefold_totp (user_id, secret, confirmed, last_step) values (?, ?, false, null)
            on conflict (user_id) do update set secret = excluded.secret, confirmed = false, last_step = null
        """

        const val FIND = "select secret, confirmed, last_step from surefold_totp where user_id = ?"

        // Two requests with one code race here, and the step compared in the statement lets one of them through.
        const val USE = """
            update surefold_totp set last_step = ?, confirmed = true
            where user_id = ? and confirmed = ? and (last_step is null or last_step < ?)
        """

        const val REMOVE = "delete from surefold_totp where user_id = ?"
    }
}
