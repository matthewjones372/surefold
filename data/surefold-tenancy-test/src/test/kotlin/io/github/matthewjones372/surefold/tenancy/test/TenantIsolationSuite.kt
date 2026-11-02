package io.github.matthewjones372.surefold.tenancy.test

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.tenancy.RlsDatabase
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import java.io.File
import java.sql.Connection

/** The kit passes a table with the guide's policy, fails one without, and the guide's changelog is the one tested. */
object TenantIsolationSuite : Suite() {
    private val marked = Regex("""<!-- tenancy-changelog -->\s*```sql\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)

    init {
        test("a table under the guide's policy keeps two tenants apart") {
            val db = RlsDatabase(bookings = false)
            db.apply("db/bookings.sql")
            db.grantTo("bookings")
            checkIsolated(TenantTransactions(db.app), ::insertBooking, ::bookingTenants)
            db.owner.connection.use { it.tenants("select tenant_id from bookings order by 1") } shouldBe
                listOf(TenantId("surefold-test-a"), TenantId("surefold-test-b"))
        }

        test("a table whose policy was left out fails the check, naming who saw whose rows") {
            val db = RlsDatabase(bookings = false)
            db.execute("create table notes (tenant_id text not null, body text not null)")
            db.grantTo("notes")
            shouldThrow<AssertionError>({
                checkIsolated(
                    TenantTransactions(db.app),
                    { connection, tenant -> connection.update("insert into notes values (?, 'n')", tenant.value) },
                    { connection -> connection.tenants("select tenant_id from notes") },
                )
            }) { failure -> failure.message shouldBe "surefold-test-a can see surefold-test-b's rows: 1 of them." }
        }

        test("a policy that hides even a tenant's own rows fails the check rather than passing it") {
            val db = RlsDatabase(bookings = false)
            db.execute(
                """
                create table notes (tenant_id text not null, body text not null);
                alter table notes enable row level security;
                alter table notes force row level security;
                create policy nothing on notes using (false) with check (true);
                """,
            )
            db.grantTo("notes")
            shouldThrow<AssertionError>({
                checkIsolated(
                    TenantTransactions(db.app),
                    { connection, tenant -> connection.update("insert into notes values (?, 'n')", tenant.value) },
                    { connection -> connection.tenants("select tenant_id from notes") },
                )
            }) { failure ->
                failure.message shouldBe "surefold-test-a cannot see its own rows, so the check proves nothing."
            }
        }

        test("docs/tenancy.md's changelog is the one these tests apply") {
            val root = System.getProperty("surefold.tenancy.test.repoRoot")!!
            val fence = marked.find(File(root, "docs/tenancy.md").readText())!!.groupValues[1]
            val resource = File(root, "data/surefold-tenancy-test/src/test/resources/db/bookings.sql").readText()
            fence.trim() shouldBe resource.trim()
        }
    }

    private fun insertBooking(connection: Connection, tenant: TenantId) =
        connection.update("insert into bookings (name) values (?)", "a booking for $tenant")

    private fun bookingTenants(connection: Connection): List<TenantId> =
        connection.tenants("select tenant_id from bookings")

    private fun Connection.update(sql: String, value: String) = prepareStatement(sql).use { statement ->
        statement.setString(1, value)
        statement.executeUpdate()
    }

    private fun Connection.tenants(sql: String): List<TenantId> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) TenantId(rows.getString(1)) else null }.toList()
        }
    }
}
