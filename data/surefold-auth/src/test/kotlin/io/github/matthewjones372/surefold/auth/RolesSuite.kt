package io.github.matthewjones372.surefold.auth

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

/** Roles granted per tenant or for every tenant, and the permissions a resolved principal carries (spec 0227). */
object RolesSuite : Suite() {
    private val user = UserId("u-1")

    init {
        test("a user with a role in one tenant has its permissions there and not in another") {
            val source = SessionsSuite.database()
            val roles = PostgresRoles(source)
            roles.grant(user, tenant = "acme", role = "venue-staff") shouldBe true
            roles.allow("venue-staff", "bookings:refund") shouldBe true
            roles.allow("venue-staff", "bookings:view") shouldBe true
            val sessions = PostgresSessions(source, roles = roles)
            val acme = sessions.resolve(sessions.create(user, tenant = "acme").token)!!
            val globex = sessions.resolve(sessions.create(user, tenant = "globex").token)!!
            acme shouldBe Principal(user, "acme", setOf("venue-staff"), setOf("bookings:refund", "bookings:view"))
            globex shouldBe Principal(user, "globex")
            globex.can("bookings:refund") shouldBe false
        }

        test("a role granted in every tenant counts in each, and with no tenant at all") {
            val roles = PostgresRoles(SessionsSuite.database())
            roles.grant(user, tenant = null, role = "support")
            roles.allow("support", "bookings:view")
            roles.grants(user, "acme") shouldBe Grants(setOf("support"), setOf("bookings:view"))
            roles.grants(user, null) shouldBe Grants(setOf("support"), setOf("bookings:view"))
        }

        test("granting twice is one grant, a role with no permissions is still held, and revoking takes it away") {
            val roles = PostgresRoles(SessionsSuite.database())
            roles.grant(user, null, "member") shouldBe true
            roles.grant(user, null, "member") shouldBe false
            roles.grant(user, "acme", "owner") shouldBe true
            roles.grants(user, "acme") shouldBe Grants(setOf("member", "owner"), emptySet())
            roles.revoke(user, null, "member") shouldBe true
            roles.revoke(user, "globex", "owner") shouldBe false
            roles.grants(user, "acme") shouldBe Grants(setOf("owner"), emptySet())
        }

        test("a permission disallowed is gone from every holder of the role at their next resolve") {
            val roles = PostgresRoles(SessionsSuite.database())
            roles.grant(user, "acme", "venue-staff")
            roles.allow("venue-staff", "bookings:refund")
            roles.disallow("venue-staff", "bookings:refund") shouldBe true
            roles.grants(user, "acme") shouldBe Grants(setOf("venue-staff"), emptySet())
        }
    }
}
