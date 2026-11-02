package dev.surefold.auth

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * How hard a password hash is to compute. The defaults are OWASP's for Argon2id: 19 MiB, two passes, one lane. Read
 * them from configuration, and raise them as hardware allows: a hash made at the old cost is rehashed at the new one
 * the next time its user signs in.
 */
data class Argon2Settings(
    val memoryKib: Int = 19_456,
    val iterations: Int = 2,
    val parallelism: Int = 1,
    val hashBytes: Int = 32,
    val saltBytes: Int = 16,
) {
    init {
        require(memoryKib >= MIN_MEMORY && iterations >= 1 && parallelism >= 1) {
            "Argon2id needs at least $MIN_MEMORY KiB, one pass and one lane."
        }
        require(hashBytes >= MIN_BYTES && saltBytes >= MIN_BYTES) { "Hash and salt are at least $MIN_BYTES bytes." }
    }

    private companion object {
        const val MIN_MEMORY = 8
        const val MIN_BYTES = 16
    }
}

/** A stored password hash, as a PHC string: `$argon2id$v=19$m=…,t=…,p=…$salt$hash`. It says its own parameters. */
@JvmInline
value class PasswordHash(val encoded: String) {
    override fun toString(): String = "PasswordHash(${encoded.substringBeforeLast('$').substringBeforeLast('$')})"
}

/** What checking a password found. */
sealed interface Checked {
    /** The password is right. [rehashed] is its hash at today's settings when the stored one was made at others. */
    data class Valid(val rehashed: PasswordHash?) : Checked

    data object Invalid : Checked
}

/** Hashes and checks passwords with Argon2id, from Bouncy Castle: pure Java, so no native library comes with it. */
class Passwords(private val settings: Argon2Settings = Argon2Settings()) {
    private val random = SecureRandom()

    fun hash(password: Secret): PasswordHash {
        val salt = ByteArray(settings.saltBytes).also(random::nextBytes)
        val cost = Cost(settings.memoryKib, settings.iterations, settings.parallelism)
        val hash = argon2(password, salt, cost, settings.hashBytes)
        return PasswordHash(
            "\$argon2id\$v=19\$m=${settings.memoryKib},t=${settings.iterations},p=${settings.parallelism}" +
                "\$${b64.encodeToString(salt)}\$${b64.encodeToString(hash)}",
        )
    }

    /** Checks [password] against [stored], comparing in constant time. Throws for a hash this did not make. */
    fun verify(password: Secret, stored: PasswordHash): Checked {
        val parts = stored.encoded.split('$')
        require(parts.size == PHC_PARTS && parts[1] == "argon2id" && parts[2] == "v=19") {
            "Not an Argon2id hash: $stored"
        }
        val named = parts[3].split(',').associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
        val cost = Cost(named.getValue("m"), named.getValue("t"), named.getValue("p"))
        val salt = b64d.decode(parts[4])
        val expected = b64d.decode(parts[5])
        val actual = argon2(password, salt, cost, expected.size)
        if (!MessageDigest.isEqual(actual, expected)) return Checked.Invalid
        val current = cost == Cost(settings.memoryKib, settings.iterations, settings.parallelism) &&
            expected.size == settings.hashBytes && salt.size == settings.saltBytes
        return Checked.Valid(if (current) null else hash(password))
    }

    private data class Cost(val memoryKib: Int, val iterations: Int, val parallelism: Int)

    private fun argon2(password: Secret, salt: ByteArray, cost: Cost, bytes: Int) =
        ByteArray(bytes).also { out ->
            val parameters = Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(cost.memoryKib)
                .withIterations(cost.iterations)
                .withParallelism(cost.parallelism)
                .withSalt(salt)
                .build()
            Argon2BytesGenerator().apply { init(parameters) }.generateBytes(password.reveal().toCharArray(), out)
        }

    private companion object {
        const val PHC_PARTS = 6

        // The PHC string format's base64: standard alphabet, no padding.
        val b64: Base64.Encoder = Base64.getEncoder().withoutPadding()
        val b64d: Base64.Decoder = Base64.getDecoder()
    }
}
