package dev.surefold.auth

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * A key for AES-GCM, from configuration as base64 of 16, 24 or 32 bytes, that encrypts what must not be readable from
 * a database dump alone. Each encryption has its own random nonce, stored before the ciphertext.
 */
class AesKey(key: Secret) {
    private val spec: SecretKeySpec

    init {
        val bytes = Base64.getDecoder().decode(key.reveal())
        require(bytes.size in KEY_SIZES) { "An AES key is 16, 24 or 32 bytes, not ${bytes.size}." }
        spec = SecretKeySpec(bytes, "AES")
    }

    /** [plain] encrypted and bound to [context]: decrypting it under any other context fails. */
    internal fun encrypt(plain: ByteArray, context: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.ENCRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return nonce + cipher.doFinal(plain)
    }

    /** Throws `AEADBadTagException` when [sealed] was not made by this key for [context]. */
    internal fun decrypt(sealed: ByteArray, context: String): ByteArray {
        val cipher = Cipher.getInstance(CIPHER)
        cipher.init(Cipher.DECRYPT_MODE, spec, GCMParameterSpec(TAG_BITS, sealed, 0, NONCE_BYTES))
        cipher.updateAAD(context.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    }

    override fun toString(): String = "AesKey(****)"

    private companion object {
        const val CIPHER = "AES/GCM/NoPadding"
        const val NONCE_BYTES = 12
        const val TAG_BITS = 128
        val KEY_SIZES = setOf(16, 24, 32)
        val random = SecureRandom()
    }
}
