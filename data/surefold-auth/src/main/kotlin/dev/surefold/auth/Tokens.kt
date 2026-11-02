package dev.surefold.auth

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Tokens handed out once and stored only as their hash: 32 random bytes as URL-safe base64, so one fits a cookie or a
 * link, and SHA-256 of it in the table, so a leaked table gives no one a session.
 */
internal object Tokens {
    private const val BYTES = 32
    private val random = SecureRandom()
    private val encoder = Base64.getUrlEncoder().withoutPadding()

    fun issue(): Secret = Secret(encoder.encodeToString(ByteArray(BYTES).also(random::nextBytes)))

    fun hash(token: Secret): ByteArray =
        MessageDigest.getInstance("SHA-256").digest(token.reveal().toByteArray(Charsets.UTF_8))

    /** Whether a row found by [stored] hash really is [token]'s, compared again in constant time. */
    fun matches(token: Secret, stored: ByteArray): Boolean = MessageDigest.isEqual(hash(token), stored)
}
