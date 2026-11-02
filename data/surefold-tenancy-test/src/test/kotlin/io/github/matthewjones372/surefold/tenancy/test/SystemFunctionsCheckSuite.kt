package io.github.matthewjones372.surefold.tenancy.test

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.tenancy.RlsDatabase
import io.github.matthewjones372.surefold.tenancy.SystemFunctions
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import org.postgresql.ds.PGSimpleDataSource
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** The check passes the guide's roles and fails each way a system role could reach rows past its functions. */
object SystemFunctionsCheckSuite : Suite() {
    private val marked =
        Regex("""<!-- system-functions-changelog -->\s*```sql\n(.*?)\n```""", RegexOption.DOT_MATCHES_ALL)
    private val roles = AtomicInteger()

    init {
        test("the guide's roles pass the check, and its function counts every tenant's rows") {
            val db = RlsDatabase(bookings = false)
            db.apply("db/bookings.sql")
            db.grantTo("bookings")
            db.apply("db/system-functions.sql")
            val expected = setOf("sys.booking_count", "public.surefold_tenancy_audit_write")
            checkSystemFunctions(db.owner, "booking_system", expected)

            val tx = TenantTransactions(db.app)
            listOf("a", "a", "b").forEach {
                tx.transaction(TenantId(it)) { c ->
                    c.createStatement().use { s ->
                        s.executeUpdate("insert into bookings (name) values ('x')")
                    }
                }
            }
            val system = PGSimpleDataSource().apply {
                setUrl((db.owner as PGSimpleDataSource).getUrl())
                user = "booking_system"
                password = "change-me"
            }
            SystemFunctions(system, "sys").call("ops", "count a's bookings", audited = true) { calls ->
                calls.one("booking_count", "a") { it.getLong(1) }
            } shouldBe 2L
        }

        test("an extra definer function the role may execute fails the check, naming it") {
            val (db, role) = granted()
            db.execute("create function sys.extra() returns int $PINNED")
            failure(db, role) shouldBe "$role may execute definer functions it is not expected to: sys.extra."
        }

        test("a definer function without a pinned search_path fails the check") {
            val (db, role) = granted()
            db.execute(
                """
                create function sys.loose() returns int language sql security definer as 'select 1';
                revoke execute on function sys.loose() from public;
                grant execute on function sys.loose() to $role;
                """,
            )
            failure(db, role, setOf("sys.ok", "sys.loose")) shouldBe
                "No pinned search_path, so a caller's schema can shadow what they use: sys.loose."
        }

        test("a table grant fails the check") {
            val (db, role) = granted()
            db.execute("grant select on bookings to $role")
            failure(db, role) shouldBe "$role holds privileges on tables or sequences: public.bookings."
        }

        test("BYPASSRLS fails the check") {
            val (db, role) = granted()
            db.execute("alter role $role bypassrls")
            failure(db, role) shouldBe "$role has BYPASSRLS, so it reads every tenant's rows."
        }

        test("an expected function the role cannot execute, and a role that does not exist, fail the check") {
            val (db, role) = granted()
            failure(db, role, setOf("sys.ok", "sys.gone")) shouldBe
                "$role cannot execute expected definer functions: sys.gone."
            failure(db, "surefold_no_such_role") shouldBe "There is no role surefold_no_such_role."
        }

        test("docs/tenancy.md's system functions changelog is the one these tests apply") {
            val root = System.getProperty("surefold.tenancy.test.repoRoot")!!
            val fence = marked.find(File(root, "docs/tenancy.md").readText())!!.groupValues[1]
            val resource = File(root, "data/surefold-tenancy-test/src/test/resources/db/system-functions.sql")
            fence.trim() shouldBe resource.readText().trim()
        }
    }

    /** A database with bookings, and a fresh role that may execute `sys.ok` and nothing else: one the check passes. */
    private fun granted(): Pair<RlsDatabase, String> {
        val role = "surefold_check_${ProcessHandle.current().pid()}_${roles.incrementAndGet()}"
        val db = RlsDatabase()
        db.execute(
            """
            create role $role nologin;
            create schema sys;
            grant usage on schema sys to $role;
            create function sys.ok() returns int $PINNED;
            revoke execute on function sys.ok() from public;
            grant execute on function sys.ok() to $role;
            """,
        )
        checkSystemFunctions(db.owner, role, setOf("sys.ok"))
        return db to role
    }

    private const val PINNED = "language sql security definer set search_path = public as 'select 1'"

    private fun failure(db: RlsDatabase, role: String, expected: Set<String> = setOf("sys.ok")): String? {
        var message: String? = null
        shouldThrow<AssertionError>({ checkSystemFunctions(db.owner, role, expected) }) { message = it.message }
        return message
    }
}
