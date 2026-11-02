package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.flatMap
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.right
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Lookup reads the lifecycle's Shared snapshot; randomness and crypto run after that transaction. */
internal class InMemoryDataKeyOperations(
    private val encrypt: (InMemoryWrappingKey, ByteArray, EncryptionContext) -> ByteArray = { key, bytes, context ->
        key.wrap(bytes, context)
    },
    private val lookup: (RootKey, KeyOperation) -> Either<KeyOperationFailure, InMemoryWrappingKey>,
) : DataKeyOperations {
    override fun generateDataKey(root: RootKey, context: EncryptionContext): Either<KeyOperationFailure, GeneratedKey> =
        lookup(root, KeyOperation.Generate).map { key ->
            val plaintext = randomBytes(KEY_BYTES)
            var transferred = false
            try {
                GeneratedKey(plaintext, encrypt(key, plaintext, context)).also { transferred = true }
            } finally {
                if (!transferred) plaintext.fill(0)
            }
        }

    override fun wrap(
        root: RootKey,
        plaintext: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> = lookup(root, KeyOperation.Wrap).map { key ->
        encrypt(key, plaintext, context)
    }

    override fun unwrap(
        root: RootKey,
        wrapped: ByteArray,
        context: EncryptionContext,
    ): Either<KeyOperationFailure, ByteArray> = lookup(root, KeyOperation.Unwrap).flatMap { key ->
        if (wrapped.size < NONCE_BYTES + TAG_BITS / Byte.SIZE_BITS) {
            invalidCiphertext()
        } else {
            try {
                key.unwrap(wrapped, context).right()
            } catch (_: AEADBadTagException) {
                invalidCiphertext()
            }
        }
    }
}

/** Related regional roots share this material; data-key arrays belong solely to their caller for erasure. */
internal class InMemoryWrappingKey {
    private val material = randomBytes(KEY_BYTES)

    fun wrap(plaintext: ByteArray, context: EncryptionContext): ByteArray {
        val nonce = randomBytes(NONCE_BYTES)
        return nonce + cipher(Cipher.ENCRYPT_MODE, nonce, context).doFinal(plaintext)
    }

    fun unwrap(wrapped: ByteArray, context: EncryptionContext): ByteArray {
        val nonce = wrapped.copyOfRange(0, NONCE_BYTES)
        return cipher(Cipher.DECRYPT_MODE, nonce, context).doFinal(
            wrapped, NONCE_BYTES,
            wrapped.size - NONCE_BYTES,
        )
    }

    private fun cipher(mode: Int, nonce: ByteArray, context: EncryptionContext): Cipher =
        Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(mode, SecretKeySpec(material, "AES"), GCMParameterSpec(TAG_BITS, nonce))
            updateAAD(contextFrame(context))
        }
}

/** Length-framed UTF-16 code units preserve even unpaired surrogates that UTF-8 replaces. */
private fun contextFrame(context: EncryptionContext): ByteArray = ByteArrayOutputStream().use { bytes ->
    DataOutputStream(bytes).use { output ->
        output.writeInt(context.entries.size)
        context.entries.toSortedMap().forEach { (key, value) ->
            output.writeText(key)
            output.writeText(value)
        }
    }
    bytes.toByteArray()
}

private fun DataOutputStream.writeText(text: String) {
    writeInt(text.length)
    text.forEach { writeChar(it.code) }
}

private fun invalidCiphertext() = KeyOperationFailure(KeyOperation.Unwrap, KeyFailureCode.InvalidCiphertext).left()

private fun randomBytes(size: Int): ByteArray = ByteArray(size).also(random::nextBytes)

private val random = SecureRandom()
private const val KEY_BYTES = 32
private const val NONCE_BYTES = 12
private const val TAG_BITS = 128
