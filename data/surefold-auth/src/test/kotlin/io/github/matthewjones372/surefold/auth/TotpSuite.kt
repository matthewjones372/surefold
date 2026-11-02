package io.github.matthewjones372.surefold.auth

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import javax.crypto.AEADBadTagException
import kotlin.time.Duration.Companion.seconds

/** RFC 6238 codes, good once and within a step either side, from secrets encrypted at rest (spec 0228). */
object TotpSuite : Suite() {
    private val noon = Instant.parse("2026-10-27T12:00:00Z")
    private val key = AesKey(Secret(Base64.getEncoder().encodeToString(ByteArray(32) { it.toByte() })))
    private val user = UserId("staff-1")

    init {
        test("RFC 6238's SHA-1 test vectors pass") {
            val secret = "12345678901234567890".toByteArray()
            val vectors = mapOf(
                59L to "94287082",
                1_111_111_109L to "07081804",
                1_111_111_111L to "14050471",
                1_234_567_890L to "89005924",
                2_000_000_000L to "69279037",
                20_000_000_000L to "65353130",
            )
            vectors.mapValues { (time, _) -> Totp.code(secret, Totp.step(Instant.ofEpochSecond(time)), digits = 8) }
                .shouldBe(vectors)
        }

        test("base32 is RFC 4648's, and the enrolment URI is one an authenticator app reads") {
            listOf("", "f", "fo", "foo", "foob", "fooba", "foobar").map { Base32.encode(it.toByteArray()) } shouldBe
                listOf("", "MY", "MZXQ", "MZXW6", "MZXW6YQ", "MZXW6YTB", "MZXW6YTBOI")
            val enrolment = PostgresTotp(SessionsSuite.database(), key).enrol(user, issuer = "Venue Bookings")
            enrolment.uri.reveal() shouldBe "otpauth://totp/Venue%20Bookings:staff-1?secret=" +
                "${enrolment.secret.reveal()}&issuer=Venue%20Bookings&algorithm=SHA1&digits=6&period=30"
            enrolment.toString() shouldBe "Enrolment(secret=Secret(****), uri=Secret(****))"
        }

        test("an enrolment verifies nothing until its first code confirms it, and a code verifies once and not again") {
            val time = TestClock(noon)
            clock.locally(time) {
                val totp = PostgresTotp(SessionsSuite.database(), key)
                val secret = decode(totp.enrol(user, "Bookings").secret)
                totp.verify(user, codeAt(secret, time.now())) shouldBe false
                val wrong = codeAt(secret, time.now()).let { it.dropLast(1) + (it.last().digitToInt() + 1) % 10 }
                totp.confirm(user, wrong) shouldBe false
                totp.confirm(user, codeAt(secret, time.now())) shouldBe true
                totp.enrolled(user) shouldBe true
                totp.verify(user, codeAt(secret, time.now())) shouldBe false // used by the confirm
                time.adjust(30.seconds)
                val code = codeAt(secret, time.now())
                totp.verify(user, code) shouldBe true
                totp.verify(user, code) shouldBe false
            }
        }

        test("a code from one step either side verifies, and from two steps does not") {
            val time = TestClock(noon)
            clock.locally(time) {
                val totp = PostgresTotp(SessionsSuite.database(), key)
                val secret = decode(totp.enrol(user, "Bookings").secret)
                totp.confirm(user, codeAt(secret, time.now())) shouldBe true
                time.adjust(120.seconds)
                totp.verify(user, codeAt(secret, time.now().minusSeconds(60))) shouldBe false
                totp.verify(user, codeAt(secret, time.now().minusSeconds(30))) shouldBe true
                totp.verify(user, codeAt(secret, time.now().plusSeconds(30))) shouldBe true
                // The step after now was used, so now's, earlier, is refused.
                totp.verify(user, codeAt(secret, time.now())) shouldBe false
            }
        }

        test("twenty threads verifying one code at once get it accepted once") {
            val time = TestClock(noon)
            clock.locally(time) {
                val totp = PostgresTotp(SessionsSuite.database(), key)
                val secret = decode(totp.enrol(user, "Bookings").secret)
                totp.confirm(user, codeAt(secret, time.now())) shouldBe true
                time.adjust(30.seconds)
                val code = codeAt(secret, time.now())
                val accepted = ConcurrentLinkedQueue<Boolean>()
                val ready = CountDownLatch(20)
                (1..20).map {
                    Thread.ofVirtual().start {
                        ready.countDown()
                        ready.await()
                        clock.locally(time) { accepted += totp.verify(user, code) }
                    }
                }.forEach { it.join() }
                accepted.count { it } shouldBe 1
            }
        }

        test("a stored secret is ciphertext, bound to its user, and useless under another key") {
            val source = SessionsSuite.database()
            val totp = PostgresTotp(source, key)
            val secret = decode(totp.enrol(user, "Bookings").secret)
            val stored = source.connection.use { connection ->
                connection.createStatement().executeQuery("select secret from surefold_totp").use {
                    it.next()
                    it.getBytes(1)
                }
            }
            stored.toList().windowed(secret.size).none { it == secret.toList() } shouldBe true
            shouldThrow<AEADBadTagException> { key.decrypt(stored, "staff-2") }
            val other = AesKey(Secret(Base64.getEncoder().encodeToString(ByteArray(32))))
            shouldThrow<AEADBadTagException> { other.decrypt(stored, user.value) }
            shouldThrow<IllegalArgumentException> { AesKey(Secret(Base64.getEncoder().encodeToString(ByteArray(7)))) }
        }
    }

    private fun codeAt(secret: ByteArray, at: Instant) = Totp.code(secret, Totp.step(at))

    /** Base32 back to bytes, as the authenticator app does. */
    fun decode(secret: Secret): ByteArray {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val bits = secret.reveal().map { alphabet.indexOf(it).toString(2).padStart(5, '0') }.joinToString("")
        return bits.chunked(8).filter { it.length == 8 }.map { it.toInt(2).toByte() }.toByteArray()
    }
}
