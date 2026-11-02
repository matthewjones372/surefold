package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

object KmsCompatibilitySuite : Suite() {
    init {
        test("an existing Kms implementer and caller need no additive operations or new error branches") {
            val kms: Kms = ExistingKms()
            val context = KeyContext(KeyOwner.Platform, KeyPurpose.Enc, 1)
            val root = (kms.createRoot(KeyOwner.Platform) as Either.Right).value
            val key = (kms.generateDataKey(root, context) as Either.Right).value
            (kms.decrypt(root, key.wrapped, context) as Either.Right).value.toList() shouldBe key.plaintext.toList()
            kms.scheduleDeletion(root, 30.days) shouldBe Either.Right(Unit)
            listOf(
                KeyError.Unavailable("later"), KeyError.Denied("refused"), KeyError.Invalid("wrong"), KeyError.NoTenant,
            ).map(::oldErrorCaller) shouldBe listOf("later", "refused", "wrong", "no tenant")
            context.entries() shouldBe mapOf("platform" to "true", "purpose" to "enc", "version" to "1")
        }
    }
}

private class ExistingKms : Kms {
    override fun createRoot(owner: KeyOwner) = Either.Right(RootKey("existing"))

    override fun generateDataKey(root: RootKey, context: KeyContext) =
        Either.Right(GeneratedKey(byteArrayOf(7), byteArrayOf(8)))

    override fun decrypt(root: RootKey, wrapped: ByteArray, context: KeyContext) = Either.Right(byteArrayOf(7))

    override fun scheduleDeletion(root: RootKey, waiting: Duration) = Either.Right(Unit)
}

private fun oldErrorCaller(error: KeyError): String = when (error) {
    is KeyError.Unavailable -> error.reason
    is KeyError.Denied -> error.reason
    is KeyError.Invalid -> error.reason
    KeyError.NoTenant -> "no tenant"
}
