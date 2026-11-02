package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.tenancy.currentTenant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** A column whose values are found by equality through a [BlindIndex]. */
data class Field(val table: String, val column: String) {
    init {
        require('\u0000' !in table && '\u0000' !in column) { "a field's table and column hold no NUL character" }
    }
}

/**
 * Equality lookups on sealed columns: `HMAC-SHA-256(bidx key, table · 0x00 · column · 0x00 · value)`, full length,
 * under the owner's `bidx` key, which is never the key that seals. The field is in the input, so two columns holding
 * one email do not share an index value; the key is the owner's, so two tenants' do not either. An index shows which
 * rows hold equal values, and how often a value recurs, and nothing more.
 *
 * The value is matched exactly: the caller passes it normalised, an email lower-cased, a phone as E.164.
 */
class BlindIndex(private val keys: KeyRing) {

    /** [value]'s index in [field] under the bound tenant's active `bidx` version: what a new row stores. */
    fun of(value: String, field: Field): Either<KeyError, ByteArray> =
        bound()?.let { of(it, value, field) } ?: KeyError.NoTenant.left()

    /** [value]'s index in [field] under [owner]'s active `bidx` version. */
    fun of(owner: KeyOwner, value: String, field: Field): Either<KeyError, ByteArray> =
        when (val key = keys.current(owner, KeyPurpose.Bidx)) {
            is Either.Left -> key
            is Either.Right -> mac(key.value, value, field).right()
        }

    /**
     * [value]'s index under every `bidx` version not yet retired, newest first, for `where email_bidx = any(?)`: a row
     * written before a rotation and one written after are both found, until the old version is retired.
     */
    fun candidates(value: String, field: Field): Either<KeyError, List<ByteArray>> =
        bound()?.let { candidates(it, value, field) } ?: KeyError.NoTenant.left()

    /** [candidates] for [owner]. */
    fun candidates(owner: KeyOwner, value: String, field: Field): Either<KeyError, List<ByteArray>> {
        val versions = when (val live = keys.versions(owner, KeyPurpose.Bidx)) {
            is Either.Left -> return live
            is Either.Right -> live.value
        }
        return versions.map { version ->
            when (val key = keys.version(owner, KeyPurpose.Bidx, version)) {
                is Either.Left -> return key
                is Either.Right -> mac(key.value, value, field)
            }
        }.right()
    }

    private fun bound(): KeyOwner? = currentTenant()?.let(KeyOwner::Tenant)

    private fun mac(key: DataKey, value: String, field: Field): ByteArray = try {
        Mac.getInstance(HMAC).run {
            init(SecretKeySpec(key.material, HMAC))
            doFinal("${field.table}\u0000${field.column}\u0000$value".toByteArray(Charsets.UTF_8))
        }
    } finally {
        key.material.fill(0)
    }

    private companion object {
        const val HMAC = "HmacSHA256"
    }
}
