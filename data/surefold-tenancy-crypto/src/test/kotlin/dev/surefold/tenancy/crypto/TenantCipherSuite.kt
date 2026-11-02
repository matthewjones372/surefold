package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import javax.crypto.Cipher

/** Values sealed under a tenant's key, bound to their place, and refused anywhere else (spec 0236). */
object TenantCipherSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))
    private val email = Place("customers", "email", "c-1")

    private fun hex(text: String): ByteArray = text.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    private fun <A> Either<SealError, A>.held(): A = (this as Either.Right).value

    init {
        test("the AES-256-GCM it seals with answers the GCM specification's test cases 13 to 16") {
            val zeros = ByteArray(32)
            val key = hex("feffe9928665731c6d6a8f9467308308feffe9928665731c6d6a8f9467308308")
            val nonce = hex("cafebabefacedbaddecaf888")
            val plaintext = hex(
                "d9313225f88406e5a55909c5aff5269a86a7a9531534f7da2e4c303d8a318a72" +
                    "1c3c0c95956809532fcf0e2449a6b525b16aedf5aa0de657ba637b391aafd255",
            )
            val ciphertext = "522dc1f099567d07f47f37a32a84427d643a8cdcbfe5c0c97598a2bd2555d1aa" +
                "8cb08e48590dbb3da7b08b1056828838c5f61e6393ba7a0abcc9f662898015ad"
            fun gcm(key: ByteArray, nonce: ByteArray, aad: ByteArray, input: ByteArray) =
                TenantCipher.gcm(Cipher.ENCRYPT_MODE, key, nonce, aad, input).hex()

            gcm(zeros, ByteArray(12), ByteArray(0), ByteArray(0)) shouldBe "530f8afbc74536b9a963b4f1c4cb738b"
            gcm(zeros, ByteArray(12), ByteArray(0), ByteArray(16)) shouldBe
                "cea7403d4d606b6e074ec5d3baf39d18" + "d0d1c8a799996bf0265b98b5d48ab919"
            gcm(key, nonce, ByteArray(0), plaintext) shouldBe ciphertext + "b094dac5d93471bdec1a502270e3cc6c"
            gcm(key, nonce, hex("feedfacedeadbeeffeedfacedeadbeefabaddad2"), plaintext.copyOf(60)) shouldBe
                ciphertext.take(120) + "76fc6ece0f4e1768cddf8853bb2d551b"
        }

        test("a value opens in its place, as the bound tenant, and twice sealed it is two different ciphertexts") {
            val cipher = TenantCipher(FixedRing())
            withTenant(TenantId("acme")) {
                val sealed = cipher.seal("jo@example.com".toByteArray(), email).held()

                cipher.open(sealed, email).held().decodeToString() shouldBe "jo@example.com"
                (cipher.seal("jo@example.com".toByteArray(), email).held().hex() == sealed.hex()) shouldBe false
            }
        }

        test("a value copied to another row, column, table or tenant fails as Tampered") {
            val cipher = TenantCipher(FixedRing())
            val sealed = cipher.seal(acme, "jo@example.com".toByteArray(), email).held()

            listOf(
                acme to email.copy(row = "c-2"),
                acme to email.copy(column = "phone"),
                acme to email.copy(table = "staff"),
                globex to email,
            ).forEach { (owner, place) -> cipher.open(owner, sealed, place) shouldBe Either.Left(SealError.Tampered) }
        }

        test("altered or truncated bytes fail as Tampered, never as an empty value") {
            val cipher = TenantCipher(FixedRing())
            val sealed = cipher.seal(acme, "jo@example.com".toByteArray(), email).held()

            cipher.open(acme, sealed.copyOf().also { it[it.size - 1] = (it.last() + 1).toByte() }, email) shouldBe
                Either.Left(SealError.Tampered)
            cipher.open(acme, sealed.copyOf(20), email) shouldBe Either.Left(SealError.Tampered)
            cipher.open(acme, sealed.copyOf().also { it[0] = 2 }, email) shouldBe Either.Left(SealError.Tampered)
        }

        test("padding makes a stored length a bucket of 16 bytes, and pad = false leaves it exact") {
            val padded = TenantCipher(FixedRing())
            val exact = TenantCipher(FixedRing(), pad = false)
            val overhead = 1 + 4 + 12 + 16

            listOf("a", "jo@example.com", "a much longer name").map { name ->
                padded.seal(acme, name.toByteArray(), email).held().size - overhead
            } shouldBe listOf(16, 32, 32)
            exact.seal(acme, "a".toByteArray(), email).held().size - overhead shouldBe 5
            exact.open(acme, exact.seal(acme, ByteArray(0), email).held(), email).held().size shouldBe 0
        }

        test("after a rotation, a version 1 value opens, reseals to version 2, and is unknown once 1 retires") {
            val ring = FixedRing()
            val cipher = TenantCipher(ring)
            val first = cipher.seal(acme, "jo@example.com".toByteArray(), email).held()

            ring.active = 2

            cipher.open(acme, first, email).held().decodeToString() shouldBe "jo@example.com"
            val second = cipher.reseal(acme, first, email).held()!!
            java.nio.ByteBuffer.wrap(second, 1, 4).int shouldBe 2
            cipher.open(acme, second, email).held().decodeToString() shouldBe "jo@example.com"
            cipher.reseal(acme, second, email).held() shouldBe null

            ring.retired += 1

            cipher.open(acme, first, email) shouldBe Either.Left(SealError.UnknownVersion(1))
            cipher.open(acme, second, email).held().decodeToString() shouldBe "jo@example.com"
        }

        test("a value in the wrong place is refused by reseal too, as Tampered") {
            val ring = FixedRing()
            val cipher = TenantCipher(ring)
            val first = cipher.seal(acme, "jo@example.com".toByteArray(), email).held()
            ring.active = 2

            cipher.reseal(acme, first, email.copy(row = "c-2")) shouldBe Either.Left(SealError.Tampered)
        }

        test("the envelope names its version, a key error is answered as one, and no tenant is NoTenant") {
            val ring = FixedRing(active = 3)
            val cipher = TenantCipher(ring)
            val sealed = cipher.seal(KeyOwner.Platform, "staff".toByteArray(), email).held()

            (sealed[0] to java.nio.ByteBuffer.wrap(sealed, 1, 4).int) shouldBe (1.toByte() to 3)
            cipher.open(KeyOwner.Platform, sealed, email).held().decodeToString() shouldBe "staff"
            cipher.seal("x".toByteArray(), email) shouldBe Either.Left(SealError.Keys(KeyError.NoTenant))
            cipher.open(acme, sealed, email).shouldBeInstanceOf<Either.Left<SealError.Tampered>>()
        }
    }
}
