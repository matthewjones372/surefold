package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import java.security.SecureRandom

/** Fixed keys, one per owner, purpose and version, as TenantKeys would answer them unwrapped, and no database. */
internal class FixedRing(var active: Int = 1) : KeyRing {
    private val keys = mutableMapOf<Triple<KeyOwner, KeyPurpose, Int>, ByteArray>()
    val retired = mutableSetOf<Int>()

    override fun current(owner: KeyOwner, purpose: KeyPurpose) = version(owner, purpose, active)

    override fun version(owner: KeyOwner, purpose: KeyPurpose, version: Int): Either<KeyError, DataKey> {
        if (version in retired || version > active) return Either.Left(KeyError.Invalid("no v$version"))
        val bytes = keys.getOrPut(Triple(owner, purpose, version)) { ByteArray(32).also(SecureRandom()::nextBytes) }
        return Either.Right(DataKey(owner, purpose, version, bytes.copyOf()))
    }

    override fun versions(owner: KeyOwner, purpose: KeyPurpose): Either<KeyError, List<Int>> =
        Either.Right((active downTo 1).filterNot { it in retired })
}
