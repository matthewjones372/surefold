package dev.surefold.auth

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow

/** Argon2id hashes that say their own cost, and a rehash when the cost changes (spec 0227). */
object PasswordsSuite : Suite() {
    // Cheap, so the suite is quick; the defaults are OWASP's.
    private val cheap = Argon2Settings(memoryKib = 64, iterations = 1)

    init {
        test("a hash verifies its password, and not another") {
            val passwords = Passwords(cheap)
            val stored = passwords.hash(Secret("correct horse"))
            passwords.verify(Secret("correct horse"), stored) shouldBe Checked.Valid(null)
            passwords.verify(Secret("correct horsf"), stored) shouldBe Checked.Invalid
        }

        test("a hash made at one cost verifies after the settings change, and answers a rehash at the new cost") {
            val stored = Passwords(cheap).hash(Secret("correct horse"))
            val raised = Passwords(cheap.copy(memoryKib = 128, iterations = 2))
            val rehashed = (raised.verify(Secret("correct horse"), stored) as Checked.Valid).rehashed!!
            rehashed.encoded.startsWith("\$argon2id\$v=19\$m=128,t=2,p=1\$") shouldBe true
            raised.verify(Secret("correct horse"), rehashed) shouldBe Checked.Valid(null)
        }

        test("the same password hashes differently each time, salted") {
            val passwords = Passwords(cheap)
            (passwords.hash(Secret("p")) == passwords.hash(Secret("p"))) shouldBe false
        }

        test("a hash made by the reference implementation verifies") {
            // libargon2, through argon2-cffi: hash_secret(b"password", b"somesalt", time_cost=2, memory_cost=64,
            // parallelism=1, hash_len=32, type=Type.ID)
            val reference = PasswordHash(
                "\$argon2id\$v=19\$m=64,t=2,p=1\$c29tZXNhbHQ\$FqGkmHNGCd0BRW2kBt6fPZ2pPmyGwwChL8FGUhTOSSI",
            )
            val raised = Passwords(cheap.copy(iterations = 2))
            // Its 8-byte salt is under the settings' 16, so the right password also answers a rehash.
            val rehashed = (raised.verify(Secret("password"), reference) as Checked.Valid).rehashed!!
            raised.verify(Secret("password"), rehashed) shouldBe Checked.Valid(null)
            raised.verify(Secret("passwore"), reference) shouldBe Checked.Invalid
        }

        test("a secret, a hash and the settings never print what they hold") {
            Secret("hunter2").toString() shouldBe "Secret(****)"
            val stored = Passwords(cheap).hash(Secret("hunter2"))
            stored.toString() shouldBe "PasswordHash(\$argon2id\$v=19\$m=64,t=1,p=1)"
            (Secret("a") == Secret("a")) shouldBe true
            (Secret("a") == Secret("b")) shouldBe false
        }

        test("a hash this did not make is refused, and settings too weak to be Argon2id are refused") {
            val bcrypt = PasswordHash("\$2b\$10\$abc")
            shouldThrow<IllegalArgumentException> { Passwords(cheap).verify(Secret("p"), bcrypt) }
            shouldThrow<IllegalArgumentException> { Argon2Settings(memoryKib = 4) }
        }
    }
}
