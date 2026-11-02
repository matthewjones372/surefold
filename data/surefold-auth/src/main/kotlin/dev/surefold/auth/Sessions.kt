package dev.surefold.auth

import java.time.Instant
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/**
 * How long a session lives: until [idle] passes with no resolve, or [absolute] since it was created, whichever comes
 * first. A resolve moves the idle expiry on only once [slide] has passed since the last that did, so a page of twenty
 * requests writes once.
 */
data class SessionSettings(
    val idle: Duration = 30.minutes,
    val absolute: Duration = 12.hours,
    val slide: Duration = 1.minutes,
) {
    init {
        require(idle.isPositive() && absolute.isPositive() && !slide.isNegative()) {
            "A session's idle and absolute lifetimes are positive, and its slide is not negative."
        }
    }
}

/** A session as stored: never its token, which only its holder has. */
data class Session(
    val id: UUID,
    val user: UserId,
    val tenant: String?,
    val createdAt: Instant,
    val idleUntil: Instant,
    val absoluteUntil: Instant,
)

/** A session just made, and its [token]: the only time the token is seen, to go in the cookie. */
data class Created(val token: Secret, val session: Session)

/** Sessions behind an opaque token, which an HTTP layer resolves from the request's cookie. */
interface Sessions {
    fun create(user: UserId, tenant: String? = null): Created

    /** Who [token] signs in, or null when it is unknown, idle too long, past its absolute expiry, or revoked. */
    fun resolve(token: Secret): Principal?

    /** A new token for [token]'s session, which then stops resolving; null when it no longer resolves. */
    fun rotate(token: Secret): Secret?

    fun revoke(token: Secret): Boolean

    /** Ends every session [user] has, on every instance: after a password change, or on "sign out everywhere". */
    fun revokeAll(user: UserId): Int
}
