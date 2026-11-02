package io.github.matthewjones372.surefold.tenancy

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.parMap
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection

/** The acting user and member role, set in every tenant transaction, for restrictive policies (spec 0237). */
object SettingsSuite : Suite() {
    private val acme = TenantId("acme")
    private val settings = listOf(Setting("app.member_role", absent = "none"), Setting("app.user_id", absent = ""))

    init {
        test("a restrictive policy on the member role hides a row from none and shows it to a manager") {
            val db = RlsDatabase()
            db.execute(
                """
                create policy managers_only on bookings as restrictive
                    using (current_setting('app.member_role') = 'manager')
                """,
            )
            val tx = TenantTransactions(db.app, db.system, settings)
            tx.transaction(acme, "app.member_role" to "manager") { it.insert("a manager's booking") }
            tx.transaction(acme, "app.member_role" to "manager") { it.names() } shouldContainExactly
                listOf("a manager's booking")
            tx.transaction(acme) { it.names() } shouldContainExactly emptyList()
            tx.transaction(acme, "app.member_role" to "staff") { it.names() } shouldContainExactly emptyList()
        }

        test("a pooled connection's next transaction sees each setting's absent value, not the last one's") {
            val db = RlsDatabase()
            db.app.connection.use { connection ->
                val single = TenantTransactions(SingleConnection(connection), settings = settings)
                single.transaction(acme, "app.member_role" to "manager", "app.user_id" to "u1") { it.read() } shouldBe
                    listOf("manager", "u1")
                single.transaction(acme) { it.read() } shouldBe listOf("none", "")
                connection.column("select coalesce(current_setting('app.member_role', true), '')") shouldBe listOf("")
            }
        }

        test("values bind beside the tenant: forks see them, and a nested withTenant without them does not") {
            val seen = withTenant(acme, "app.member_role" to "manager") {
                (
                    attempt<Nothing, List<String?>> { parMap(1..20) { tenantValues.get()["app.member_role"] } } as
                        Either.Right
                    ).value
            }
            seen.toSet() shouldBe setOf("manager")
            withTenant(acme, "app.member_role" to "manager") {
                withTenant(TenantId("globex")) { tenantValues.get() } shouldBe emptyMap()
            }
        }

        test("a setting is named word.word, is declared once, and is never the tenant's own") {
            shouldThrow<IllegalArgumentException> { Setting("member_role", absent = "none") }
            shouldThrow<IllegalArgumentException> { Setting("app.role'; drop", absent = "none") }
            shouldThrow<IllegalArgumentException> { Setting(TENANT_SETTING, absent = "") }
            shouldThrow<IllegalArgumentException> { withTenant(acme, "role" to "manager") { } }
            shouldThrow<IllegalArgumentException> {
                TenantTransactions(
                    PGSimpleDataSource(),
                    settings =
                    settings + settings,
                )
            }
        }
    }

    private fun Connection.read(): List<Any?> =
        column("select current_setting('app.member_role') || ',' || current_setting('app.user_id')")
            .single().toString().split(",")

    private fun Connection.insert(name: String) =
        prepareStatement("insert into bookings (name) values (?)").use { statement ->
            statement.setString(1, name)
            statement.executeUpdate()
        }

    private fun Connection.names(): List<Any?> = column("select name from bookings order by name")

    private fun Connection.column(sql: String): List<Any?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getObject(1) else null }.toList()
        }
    }
}
