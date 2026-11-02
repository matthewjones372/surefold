package dev.surefold.auth

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate
import java.time.Instant
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

/** Sessions that die idle or old, rotate, and revoke across instances, behind tokens stored only hashed (spec 0227). */
object SessionsSuite : Suite() {
    private val noon = Instant.parse("2026-10-27T12:00:00Z")
    private val settings = SessionSettings(idle = 30.minutes, absolute = 12.hours)
    private val user = UserId("u-1")

    init {
        test("a session resolves to its user and tenant, and an unknown token to nothing") {
            val sessions = PostgresSessions(database(), settings)
            val (token, _) = sessions.create(user, tenant = "acme")
            sessions.resolve(token) shouldBe Principal(user, "acme")
            sessions.resolve(Tokens.issue()) shouldBe null
        }

        test("a session left unresolved dies after idle, and one resolved often enough lives until absolute") {
            val time = TestClock(noon)
            clock.locally(time) {
                val sessions = PostgresSessions(database(), settings)
                val idle = sessions.create(user).token
                val busy = sessions.create(user).token
                repeat(24) { // to 11h36m since created
                    time.adjust(29.minutes)
                    (sessions.resolve(busy) != null) shouldBe true
                }
                sessions.resolve(idle) shouldBe null
                time.adjust(23.minutes) // 11h59m, then just past 12h
                (sessions.resolve(busy) != null) shouldBe true
                time.adjust(2.minutes)
                sessions.resolve(busy) shouldBe null
            }
        }

        test("a resolve within the slide does not move the idle expiry") {
            val time = TestClock(noon)
            clock.locally(time) {
                val sessions = PostgresSessions(database(), settings.copy(slide = 10.minutes))
                val token = sessions.create(user).token
                time.adjust(5.minutes)
                (sessions.resolve(token) != null) shouldBe true // within the slide: idle still ends at 12:30
                time.adjust(26.minutes)
                sessions.resolve(token) shouldBe null
            }
        }

        test("a rotated token stops resolving, and its successor carries the same session") {
            val sessions = PostgresSessions(database(), settings)
            val old = sessions.create(user, tenant = "acme").token
            val next = sessions.rotate(old)!!
            sessions.resolve(old) shouldBe null
            sessions.resolve(next) shouldBe Principal(user, "acme")
            sessions.rotate(old) shouldBe null
        }

        test("a token revoked on one instance stops on another, and revoking a user ends all their sessions") {
            val source = database()
            val one = PostgresSessions(source, settings)
            val other = PostgresSessions(source, settings)
            val token = one.create(user).token
            (other.resolve(token) != null) shouldBe true
            one.revoke(token) shouldBe true
            other.resolve(token) shouldBe null
            val (a, b) = List(2) { other.create(user).token }
            val elsewhere = other.create(UserId("u-2")).token
            one.revokeAll(user) shouldBe 2
            listOf(other.resolve(a), other.resolve(b)) shouldBe listOf(null, null)
            (other.resolve(elsewhere) != null) shouldBe true
        }

        test("the table holds only a token's hash, and a principal carries the roles its source grants") {
            val source = database()
            val roles = RoleSource { _, tenant ->
                if (tenant == "acme") Grants(setOf("venue-staff"), setOf("bookings:refund")) else Grants.none
            }
            val sessions = PostgresSessions(source, settings, roles)
            val token = sessions.create(user, tenant = "acme").token
            val stored = source.connection.use { connection ->
                connection.createStatement().executeQuery("select token_hash from surefold_sessions").use {
                    it.next()
                    it.getBytes(1)
                }
            }
            stored.contentEquals(Tokens.hash(token)) shouldBe true
            sessions.resolve(token)!!.can("bookings:refund") shouldBe true
            token.toString() shouldBe "Secret(****)"
        }

        test("prune deletes sessions past either expiry and keeps the rest") {
            val time = TestClock(noon)
            clock.locally(time) {
                val sessions = PostgresSessions(database(), settings)
                sessions.create(user)
                time.adjust(20.minutes)
                val live = sessions.create(user).token
                time.adjust(15.minutes)
                sessions.prune() shouldBe 1
                (sessions.resolve(live) != null) shouldBe true
            }
        }
    }

    fun database(): DataSource = Postgres.empty().also { source ->
        source.migrate("surefold/auth/postgres.sql")
    }
}
