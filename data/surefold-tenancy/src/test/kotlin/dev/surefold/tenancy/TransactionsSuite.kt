package dev.surefold.tenancy

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import java.sql.Connection
import java.sql.SQLException

/** Transactions Postgres scopes to one tenant, and an audited way across them (spec 0225). */
object TransactionsSuite : Suite() {
    private val acme = TenantId("acme")
    private val globex = TenantId("globex")

    init {
        test("a transaction as one tenant reads none of another's rows, and writes only its own") {
            val db = RlsDatabase()
            val tx = TenantTransactions(db.app, db.system)
            tx.transaction(acme) { it.insert("acme's booking") }
            tx.transaction(globex) { it.insert("globex's booking") }
            tx.transaction(acme) { it.names() } shouldContainExactly listOf("acme's booking")
            tx.transaction(globex) { it.names() } shouldContainExactly listOf("globex's booking")
            shouldThrow<SQLException> {
                tx.transaction(acme) { connection ->
                    connection.insert("x", tenant = "globex")
                }
            }
        }

        test("a transaction with no tenant is refused, and opens nothing") {
            val db = RlsDatabase()
            TenantTransactions(db.app, db.system).transaction { it.names() } shouldBe
                Either.Left(TenancyError.NoTenant)
        }

        test("a system transaction reads every tenant's rows, and leaves an audit row saying who and why") {
            val db = RlsDatabase()
            val tx = TenantTransactions(db.app, db.system)
            tx.transaction(acme) { it.insert("a") }
            tx.transaction(globex) { it.insert("g") }
            tx.system(actor = "jobs-worker", reason = "claim every tenant's jobs") { it.names() } shouldContainExactly
                listOf("a", "g")
            db.owner.connection.use { it.column("select actor || ': ' || reason from surefold_tenancy_audit") }
                .shouldBe(listOf("jobs-worker: claim every tenant's jobs"))
        }

        test("a system transaction that throws rolls back, its audit row with it") {
            val db = RlsDatabase()
            val tx = TenantTransactions(db.app, db.system)
            shouldThrow<IllegalStateException> {
                tx.system("ops:sam", "fix a booking") { connection ->
                    connection.insert("x", tenant = "acme")
                    error("changed my mind")
                }
            }
            db.owner.connection.use { it.column("select count(*) from surefold_tenancy_audit") } shouldBe listOf(0L)
            db.owner.connection.use { it.column("select count(*) from bookings") } shouldBe listOf(0L)
        }

        test("the tenant is set for its transaction only, so a pooled connection carries none to its next use") {
            val db = RlsDatabase()
            val tx = TenantTransactions(db.app, db.system)
            db.app.connection.use { connection ->
                val single = TenantTransactions(SingleConnection(connection))
                single.transaction(acme) { it.insert("a") }
                connection.column("select coalesce(current_setting('app.tenant_id', true), '')") shouldBe listOf("")
            }
            tx.transaction(acme) { it.names() } shouldContainExactly listOf("a")
        }

        test("a system transaction needs a system DataSource, and says who and why") {
            val db = RlsDatabase()
            shouldThrow<IllegalStateException> { TenantTransactions(db.app).system("ops", "look") { } }
            shouldThrow<IllegalArgumentException> { TenantTransactions(db.app, db.system).system("", "look") { } }
        }
    }

    private fun Connection.insert(name: String, tenant: String? = null) =
        prepareStatement(
            if (tenant == null) {
                "insert into bookings (name) values (?)"
            } else {
                "insert into bookings (name, tenant_id) values (?, ?)"
            },
        ).use { statement ->
            statement.setString(1, name)
            if (tenant != null) statement.setString(2, tenant)
            statement.executeUpdate()
        }

    private fun Connection.names(): List<Any?> = column("select name from bookings order by name")

    private fun Connection.column(sql: String): List<Any?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getObject(1) else null }.toList()
        }
    }
}
