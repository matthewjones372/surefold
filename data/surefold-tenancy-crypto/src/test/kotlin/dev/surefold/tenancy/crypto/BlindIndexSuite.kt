package dev.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Equality lookups under each owner's bidx key, field by field, across a rotation (spec 0236). */
object BlindIndexSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))
    private val email = Field("customers", "email")

    private fun <A> Either<KeyError, A>.held(): A = (this as Either.Right).value

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    init {
        test("an index is HMAC-SHA-256 of the field and the value under the bidx key, full length") {
            val ring = FixedRing()
            val key = (ring.current(acme, KeyPurpose.Bidx) as Either.Right).value.material
            val expected = Mac.getInstance("HmacSHA256").run {
                init(SecretKeySpec(key, "HmacSHA256"))
                doFinal("customers\u0000email\u0000jo@example.com".toByteArray())
            }

            BlindIndex(ring).of(acme, "jo@example.com", email).held().hex() shouldBe expected.hex()
        }

        test("one value indexes alike in one field, and differently in another field or another tenant") {
            val index = BlindIndex(FixedRing())
            val once = index.of(acme, "jo@example.com", email).held().hex()

            index.of(acme, "jo@example.com", email).held().hex() shouldBe once
            (index.of(acme, "jo@example.com", Field("staff", "email")).held().hex() == once) shouldBe false
            (index.of(globex, "jo@example.com", email).held().hex() == once) shouldBe false
            withTenant(TenantId("acme")) { index.of("jo@example.com", email).held().hex() } shouldBe once
        }

        test("candidates are one index per live version, newest first, and a retired version drops out") {
            val ring = FixedRing()
            val index = BlindIndex(ring)
            val first = index.of(acme, "jo@example.com", email).held().hex()
            ring.active = 2
            val second = index.of(acme, "jo@example.com", email).held().hex()

            index.candidates(acme, "jo@example.com", email).held().map { it.hex() } shouldBe listOf(second, first)

            ring.retired += 1
            index.candidates(acme, "jo@example.com", email).held().map { it.hex() } shouldBe listOf(second)
        }

        test("no tenant bound is NoTenant, and a field cannot hide a separator in its name") {
            BlindIndex(FixedRing()).of("jo@example.com", email) shouldBe Either.Left(KeyError.NoTenant)
            shouldThrow<IllegalArgumentException> { Field("customers\u0000x", "email") }
        }
    }
}
