package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.time.Duration

/**
 * A [Kms] in process memory, for tests and local runs: each root is an AES-256 key, and a data key is wrapped under it
 * with AES-GCM, the context as associated data, so another context's unwrap fails as a KMS's does. A root scheduled
 * for deletion is denied at once. Set [unavailable] to have every call answer [KeyError.Unavailable].
 */
class InMemoryKms : Kms {

    private val random = SecureRandom()
    private val roots = ConcurrentHashMap<RootKey, ByteArray>()
    private val deleted = ConcurrentHashMap.newKeySet<RootKey>()
    private val decrypted = AtomicInteger()

    @Volatile
    var unavailable: Boolean = false

    /** How many [decrypt] calls this KMS has answered, whatever it answered them. */
    val decrypts: Int get() = decrypted.get()

    override fun createRoot(owner: KeyOwner): Either<KeyError, RootKey> = reachable {
        RootKey("in-memory/${UUID.randomUUID()}").also { roots[it] = bytes(KEY_BYTES) }.right()
    }

    override fun generateDataKey(root: RootKey, context: KeyContext): Either<KeyError, GeneratedKey> = reachable {
        when (val held = held(root)) {
            is Either.Left -> held

            is Either.Right -> {
                val plaintext = bytes(KEY_BYTES)
                val nonce = bytes(NONCE_BYTES)
                val sealed = cipher(Cipher.ENCRYPT_MODE, held.value, nonce, context).doFinal(plaintext)
                GeneratedKey(plaintext, wrapped = nonce + sealed).right()
            }
        }
    }

    override fun decrypt(root: RootKey, wrapped: ByteArray, context: KeyContext): Either<KeyError, ByteArray> {
        decrypted.incrementAndGet()
        return reachable {
            when (val held = held(root)) {
                is Either.Left -> held

                is Either.Right ->
                    if (wrapped.size <= NONCE_BYTES) {
                        KeyError.Invalid("${wrapped.size} bytes are too few to be a wrapped key").left()
                    } else {
                        try {
                            val nonce = wrapped.copyOfRange(0, NONCE_BYTES)
                            cipher(Cipher.DECRYPT_MODE, held.value, nonce, context)
                                .doFinal(wrapped, NONCE_BYTES, wrapped.size - NONCE_BYTES)
                                .right()
                        } catch (_: AEADBadTagException) {
                            KeyError.Invalid("not wrapped by ${root.id} under $context").left()
                        }
                    }
            }
        }
    }

    override fun scheduleDeletion(root: RootKey, waiting: Duration): Either<KeyError, Unit> = reachable {
        when (val held = held(root)) {
            is Either.Left -> held

            is Either.Right -> {
                deleted.add(root)
                Unit.right()
            }
        }
    }

    private fun <A> reachable(call: () -> Either<KeyError, A>): Either<KeyError, A> =
        if (unavailable) KeyError.Unavailable("the in-memory KMS is set unavailable").left() else call()

    private fun held(root: RootKey): Either<KeyError, ByteArray> = when {
        root in deleted -> KeyError.Denied("${root.id} is scheduled for deletion").left()
        else -> roots[root]?.right() ?: KeyError.Invalid("${root.id} is not a root this KMS made").left()
    }

    private fun bytes(count: Int): ByteArray = ByteArray(count).also(random::nextBytes)

    private fun cipher(mode: Int, root: ByteArray, nonce: ByteArray, context: KeyContext): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(root, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(context.entries().toSortedMap().toString().toByteArray(Charsets.UTF_8))
        }

    private companion object {
        const val KEY_BYTES = 32
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
    }
}
