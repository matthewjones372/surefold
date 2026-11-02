package dev.surefold.auth

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import java.io.File
import java.time.Instant
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.seconds

/** docs/auth.md's example is GuideExample.kt, and it signs in with a password, a second factor and roles. */
object AuthGuideSuite : Suite() {
    private val marked = Regex("""<!-- auth -->\s*```kotlin\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
    private val cheap = Argon2Settings(memoryKib = 64, iterations = 1)
    private val key = AesKey(Secret(Base64.getEncoder().encodeToString(ByteArray(32))))

    init {
        test("the guide's example is GuideExample.kt from its first declaration down") {
            val root = System.getProperty("surefold.auth.repoRoot")
            expect(root != null) { "the build must pass -Dsurefold.auth.repoRoot; see its build file" }
            val page = File(root!!, "docs/auth.md").readText()
            val fence = marked.findAll(page).map { it.groupValues[1] }.single()
            val source = File(root, EXAMPLE).readText()
            source.substring(source.indexOf("/** Where")).trim() shouldBe fence.trim()
        }

        test("a right password signs in to a session with the user's roles, and a wrong one or a stranger is refused") {
            val source = SessionsSuite.database()
            val users = InMemoryUsers().apply { add("ada@example.com", UserId("u-1"), Passwords(cheap)) }
            val roles = PostgresRoles(source).apply {
                grant(UserId("u-1"), "acme", "venue-staff")
                allow("venue-staff", "bookings:refund")
            }
            val sessions = PostgresSessions(source, roles = roles)
            val accounts = Accounts(users, Passwords(cheap), PostgresTotp(source, key), sessions)
            val signed = accounts.signIn("ada@example.com", Secret("correct horse"), null, "acme") as SignedIn.Session
            sessions.resolve(signed.token)!!.can("bookings:refund") shouldBe true
            accounts.signIn("ada@example.com", Secret("wrong"), null, "acme") shouldBe SignedIn.Refused
            accounts.signIn("bob@example.com", Secret("correct horse"), null, "acme") shouldBe SignedIn.Refused
        }

        test("a user with a second factor is asked for its code, and signs in with it") {
            val time = TestClock(Instant.parse("2026-10-27T12:00:00Z"))
            clock.locally(time) {
                val source = SessionsSuite.database()
                val users = InMemoryUsers().apply { add("ada@example.com", UserId("u-1"), Passwords(cheap)) }
                val totp = PostgresTotp(source, key)
                val secret = TotpSuite.decode(totp.enrol(UserId("u-1"), "Bookings").secret)
                totp.confirm(UserId("u-1"), Totp.code(secret, Totp.step(time.now()))) shouldBe true
                time.adjust(30.seconds)
                val accounts = Accounts(users, Passwords(cheap), totp, PostgresSessions(source))
                accounts.signIn("ada@example.com", Secret("correct horse"), null, "acme") shouldBe SignedIn.CodeNeeded
                val code = Totp.code(secret, Totp.step(time.now()))
                (accounts.signIn("ada@example.com", Secret("correct horse"), code, "acme") is SignedIn.Session) shouldBe
                    true
            }
        }

        test("a hash made at an old cost is replaced at sign-in") {
            val users = InMemoryUsers().apply { add("ada@example.com", UserId("u-1"), Passwords(cheap)) }
            val source = SessionsSuite.database()
            val raised = Passwords(cheap.copy(iterations = 2))
            Accounts(users, raised, PostgresTotp(source, key), PostgresSessions(source))
                .signIn("ada@example.com", Secret("correct horse"), null, "acme")
            raised.verify(Secret("correct horse"), users.find("ada@example.com")!!.second) shouldBe
                Checked.Valid(null)
        }
    }

    private class InMemoryUsers : Users {
        private val byEmail = ConcurrentHashMap<String, Pair<UserId, PasswordHash>>()

        fun add(email: String, user: UserId, passwords: Passwords) {
            byEmail[email] = user to passwords.hash(Secret("correct horse"))
        }

        override fun find(email: String) = byEmail[email]

        override fun store(user: UserId, hash: PasswordHash) {
            byEmail.replaceAll { _, held -> if (held.first == user) user to hash else held }
        }
    }

    private const val EXAMPLE =
        "data/surefold-auth/src/test/kotlin/dev/surefold/auth/GuideExample.kt"
}
