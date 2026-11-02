package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.tenancy.TenantId
import kotlin.time.Duration

/** Whose keys these are: a tenant's, or the platform's own, for data that belongs to no tenant. */
sealed interface KeyOwner {

    data class Tenant(val id: TenantId) : KeyOwner

    data object Platform : KeyOwner
}

/** What a data key is for: sealing values, or a blind index to look them up by (spec 0236). */
enum class KeyPurpose(val label: String) {
    Enc("enc"),
    Bidx("bidx"),
}

/**
 * What a data key is wrapped under and must be unwrapped under again: a KMS refuses an unwrap under any other, so a key
 * policy can refuse one tenant's caller another tenant's keys.
 */
data class KeyContext(val owner: KeyOwner, val purpose: KeyPurpose, val version: Int) {

    init {
        require(version >= 1) { "a key version starts at 1, and this one is $version" }
    }

    /** The context as a KMS takes it: `tenant_id` names a tenant's owner, and `platform` the platform's. */
    fun entries(): Map<String, String> = mapOf(
        when (owner) {
            is KeyOwner.Tenant -> "tenant_id" to owner.id.value
            KeyOwner.Platform -> "platform" to "true"
        },
        "purpose" to purpose.label,
        "version" to version.toString(),
    )
}

/** An owner's root key in the KMS, by the id the KMS answered when it made it. */
@JvmInline
value class RootKey(val id: String)

/** A new data key: its bytes, for this process only, and the same bytes wrapped under a root key, for the table. */
class GeneratedKey(val plaintext: ByteArray, val wrapped: ByteArray) {

    override fun toString(): String = "GeneratedKey(${wrapped.size} bytes wrapped)"
}

/** Why a KMS call answered nothing. */
sealed interface KeyError {

    /** The KMS could not be reached, or throttled the call: a later call may succeed. */
    data class Unavailable(val reason: String) : KeyError

    /** The KMS refused: the root key is deleted, disabled, or a policy keeps it from this caller. */
    data class Denied(val reason: String) : KeyError

    /** Not a key this root wrapped under this context: the bytes, the root or the context is wrong. */
    data class Invalid(val reason: String) : KeyError

    /** No tenant was bound, so there is no one whose key to answer. */
    data object NoTenant : KeyError
}

/** The calls a key per tenant needs of a KMS, each answering its failure as a value. */
interface Kms {

    /** A new root key for [owner], which only this KMS holds. */
    fun createRoot(owner: KeyOwner): Either<KeyError, RootKey>

    /** A new data key, wrapped under [root] and [context]. */
    fun generateDataKey(root: RootKey, context: KeyContext): Either<KeyError, GeneratedKey>

    /** [wrapped]'s bytes, if [root] wrapped them under exactly [context]. */
    fun decrypt(root: RootKey, wrapped: ByteArray, context: KeyContext): Either<KeyError, ByteArray>

    /** Schedules [root]'s deletion after [waiting]; from then on it unwraps nothing, and once deleted, never again. */
    fun scheduleDeletion(root: RootKey, waiting: Duration): Either<KeyError, Unit>
}
