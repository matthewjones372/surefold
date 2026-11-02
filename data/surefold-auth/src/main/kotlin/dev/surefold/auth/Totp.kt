package dev.surefold.auth

import java.nio.ByteBuffer
import java.time.Instant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** RFC 6238 time-based one-time passwords: HMAC-SHA1 over 30-second steps since the epoch. */
internal object Totp {
    const val STEP_SECONDS = 30L
    const val DIGITS = 6
    private val powers = (0..9).map { power -> (1..power).fold(1) { acc, _ -> acc * 10 } }

    fun step(at: Instant): Long = Math.floorDiv(at.epochSecond, STEP_SECONDS)

    /** RFC 4226's HOTP of [secret] at [step]: dynamic truncation, then the low [digits] decimal digits, zero-padded. */
    fun code(secret: ByteArray, step: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(step).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ByteBuffer.wrap(hash, offset, Int.SIZE_BYTES).int and Int.MAX_VALUE
        return (binary % powers[digits]).toString().padStart(digits, '0')
    }
}

/** RFC 4648 base32, unpadded: how authenticator apps take a TOTP secret. */
internal object Base32 {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
    private const val BITS = 5
    private const val MASK = 0x1f
    private const val BYTE = 0xff

    fun encode(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (byte in bytes) {
            buffer = (buffer shl Byte.SIZE_BITS) or (byte.toInt() and BYTE)
            bits += Byte.SIZE_BITS
            while (bits >= BITS) {
                append(ALPHABET[(buffer shr (bits - BITS)) and MASK])
                bits -= BITS
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (BITS - bits)) and MASK])
    }
}
