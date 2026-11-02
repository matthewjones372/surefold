package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.tenancy.TenantId
import kotlin.time.Duration.Companion.days

/** The in-memory KMS refuses what a real one refuses: another context, another root, a deleted root (spec 0235). */
object InMemoryKmsSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))

    private fun <A> Either<KeyError, A>.held(): A = (this as Either.Right).value

    init {
        test("a key wrapped under one tenant's context unwraps under it, and not under another tenant's") {
            val kms = InMemoryKms()
            val root = kms.createRoot(acme).held()
            val key = kms.generateDataKey(root, KeyContext(acme, KeyPurpose.Enc, 1)).held()

            kms.decrypt(root, key.wrapped, KeyContext(acme, KeyPurpose.Enc, 1)).held().toList() shouldBe
                key.plaintext.toList()
            kms.decrypt(root, key.wrapped, KeyContext(globex, KeyPurpose.Enc, 1))
                .shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }

        test("another purpose, another version or another root does not unwrap it either") {
            val kms = InMemoryKms()
            val root = kms.createRoot(acme).held()
            val other = kms.createRoot(acme).held()
            val key = kms.generateDataKey(root, KeyContext(acme, KeyPurpose.Enc, 1)).held()

            kms.decrypt(root, key.wrapped, KeyContext(acme, KeyPurpose.Bidx, 1)).shouldBeInstanceOf<Either.Left<*>>()
            kms.decrypt(root, key.wrapped, KeyContext(acme, KeyPurpose.Enc, 2)).shouldBeInstanceOf<Either.Left<*>>()
            kms.decrypt(other, key.wrapped, KeyContext(acme, KeyPurpose.Enc, 1)).shouldBeInstanceOf<Either.Left<*>>()
        }

        test("a root scheduled for deletion is denied, for unwrapping and for new keys") {
            val kms = InMemoryKms()
            val root = kms.createRoot(KeyOwner.Platform).held()
            val context = KeyContext(KeyOwner.Platform, KeyPurpose.Enc, 1)
            val key = kms.generateDataKey(root, context).held()

            kms.scheduleDeletion(root, waiting = 30.days) shouldBe Either.Right(Unit)

            kms.decrypt(root, key.wrapped, context).shouldBeInstanceOf<Either.Left<KeyError.Denied>>()
            kms.generateDataKey(root, context).shouldBeInstanceOf<Either.Left<KeyError.Denied>>()
        }

        test("an unavailable KMS answers Unavailable, and counts the unwraps it was asked for") {
            val kms = InMemoryKms()
            val root = kms.createRoot(acme).held()
            val key = kms.generateDataKey(root, KeyContext(acme, KeyPurpose.Enc, 1)).held()

            kms.unavailable = true

            kms.decrypt(root, key.wrapped, KeyContext(acme, KeyPurpose.Enc, 1))
                .shouldBeInstanceOf<Either.Left<KeyError.Unavailable>>()
            kms.createRoot(globex).shouldBeInstanceOf<Either.Left<KeyError.Unavailable>>()
            kms.decrypts shouldBe 1
        }

        test("a context names a tenant by tenant_id and the platform by platform, and a key's bytes stay out of logs") {
            KeyContext(acme, KeyPurpose.Bidx, 3).entries() shouldBe
                mapOf("tenant_id" to "acme", "purpose" to "bidx", "version" to "3")
            KeyContext(KeyOwner.Platform, KeyPurpose.Enc, 1).entries() shouldBe
                mapOf("platform" to "true", "purpose" to "enc", "version" to "1")
            GeneratedKey(ByteArray(32), ByteArray(60)).toString() shouldBe "GeneratedKey(60 bytes wrapped)"
        }
    }
}
