package io.github.matthewjones372.surefold.tenancy

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import org.postgresql.ds.PGSimpleDataSource
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException

/** Crossing tenants on a role that may only execute definer functions, so no SQL it runs reads a row (spec 0237). */
object SystemFunctionsSuite : Suite() {
    init {
        test("on a role with only EXECUTE, a call returns the function's rows and writes an audit row") {
            val db = database()
            val sys = SystemFunctions(db.functions, schema = "sys")
            val names = sys.call(actor = "api", reason = "list a tenant's bookings", audited = true) { calls ->
                calls.query("booking_names", "acme") { it.getString("name") }
            }
            names shouldContainExactly listOf("a", "b")
            sys.call("api", "count") { it.one("booking_count", "globex") { row -> row.getLong(1) } } shouldBe 1L
            sys.call("api", "look") { it.optional("booking_names", "initech") { row -> row.getString(1) } } shouldBe
                null
            db.owner.connection.use { it.column("select actor || ': ' || reason from surefold_tenancy_audit") }
                .shouldBe(listOf("api: list a tenant's bookings"))
        }

        test("a plain select on the same role is refused by Postgres, on the table and the audit table alike") {
            val db = database()
            listOf("select name from bookings", "select actor from surefold_tenancy_audit").forEach { sql ->
                shouldThrow<SQLException>({ db.functions.connection.use { it.column(sql) } }) { refused ->
                    refused.sqlState shouldBe "42501"
                }
            }
        }

        test("one fails on a function that returns no row, and a call that throws writes no audit row") {
            val db = database()
            val sys = SystemFunctions(db.functions, "sys")
            shouldThrow<IllegalStateException> {
                sys.call("ops:sam", "look", audited = true) { calls ->
                    calls.one("booking_names", "initech") { it.getString(1) }
                }
            }
            db.owner.connection.use { it.column("select count(*) from surefold_tenancy_audit") } shouldBe listOf(0L)
        }

        test("names are identifiers, and a call says who makes it and why") {
            shouldThrow<IllegalArgumentException> { SystemFunctions(PGSimpleDataSource(), "sys; drop") }
            val sys = SystemFunctions(PGSimpleDataSource(), "sys")
            shouldThrow<IllegalArgumentException> { sys.call("", "look") { } }
            shouldThrow<IllegalArgumentException> {
                FunctionCalls(unused(), "sys").query("x()--") { }
            }
        }
    }

    private fun database(): RlsDatabase = RlsDatabase().also { db ->
        val tx = TenantTransactions(db.app)
        tx.transaction(TenantId("acme")) { it.update("insert into bookings (name) values ('a'), ('b')") }
        tx.transaction(TenantId("globex")) { it.update("insert into bookings (name) values ('g')") }
        db.execute(FUNCTIONS)
    }

    /** A connection that fails on any use, for checks that must refuse before reaching the database. */
    private fun unused(): Connection =
        Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, _ ->
            throw UnsupportedOperationException(method.name)
        } as Connection

    private const val FUNCTIONS = """
        create schema sys;
        grant usage on schema sys to surefold_functions;
        create function sys.booking_names(tenant text) returns table (name text)
            language sql security definer set search_path = public, pg_temp
            as $$ select name from bookings where tenant_id = tenant order by name $$;
        create function sys.booking_count(tenant text) returns bigint
            language sql security definer set search_path = public, pg_temp
            as $$ select count(*) from bookings where tenant_id = tenant $$;
        revoke execute on all functions in schema sys from public;
        grant execute on all functions in schema sys to surefold_functions;
    """

    private fun Connection.update(sql: String) = createStatement().use { it.executeUpdate(sql) }

    private fun Connection.column(sql: String): List<Any?> = createStatement().use { statement ->
        statement.executeQuery(sql).use { rows ->
            generateSequence { if (rows.next()) rows.getObject(1) else null }.toList()
        }
    }
}
