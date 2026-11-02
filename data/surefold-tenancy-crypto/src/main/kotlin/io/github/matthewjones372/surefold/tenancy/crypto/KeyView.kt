package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either

/** A revocable handle; each admitted callback owns a temporary copy which is wiped before return. */
interface KeyView : AutoCloseable {
    val identity: KeyIdentity
    fun <A> using(block: (ByteArray) -> A): Either<KeyError, A>
    override fun close()
}
