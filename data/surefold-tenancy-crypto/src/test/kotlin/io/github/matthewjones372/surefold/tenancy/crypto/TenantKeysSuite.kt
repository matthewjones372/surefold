package io.github.matthewjones372.surefold.tenancy.crypto

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import io.github.matthewjones372.surefold.tenancy.RlsDatabase
import io.github.matthewjones372.surefold.tenancy.SingleConnection
import io.github.matthewjones372.surefold.tenancy.TenantId
import io.github.matthewjones372.surefold.tenancy.TenantTransactions
import io.github.matthewjones372.surefold.tenancy.withTenant
import org.postgresql.PGConnection
import java.sql.Connection
import java.sql.ResultSet
import kotlin.time.Duration.Companion.days

/** Every owner's keys in Postgres, under its own root, and one tenant's rows out of another's reach (spec 0235). */
object TenantKeysSuite : Suite() {

    private val acme = KeyOwner.Tenant(TenantId("acme"))
    private val globex = KeyOwner.Tenant(TenantId("globex"))

    private class Fixture {
        val database = RlsDatabase(bookings = false).apply {
            apply("surefold/tenancy-crypto/postgres.sql")
            grantTo("surefold_tenant_keys")
        }
        val tenants = TenantTransactions(database.app, database.system)
        val keys = TenantKeys(tenants, InMemoryKms())
    }

    /** One key row as a copy of the database holds it. */
    private class Copied(val purpose: String, val version: Int, val wrapped: ByteArray, val root: String)

    /** Every key row [connection] can read, as a copy taken now would hold them. */
    private fun copied(connection: Connection): List<Copied> = connection.createStatement().use { statement ->
        statement.executeQuery("select purpose, version, wrapped, root from surefold_tenant_keys").use { rows ->
            generateSequence { rows.takeIf { it.next() }?.let(::copiedRow) }.toList()
        }
    }

    private fun copiedRow(row: ResultSet) = Copied(row.getString(1), row.getInt(2), row.getBytes(3), row.getString(4))

    private fun <A> Either<KeyError, A>.held(): A = (this as Either.Right).value

    private fun DataKey.bytes() = material.toList()

    init {
        test("rotation makes version 2 current, and version 1 still opens what it wrote") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            val first = keys.current(acme, KeyPurpose.Enc).held()

            keys.rotate(acme).held()

            val second = keys.current(acme, KeyPurpose.Enc).held()
            (first.version to second.version) shouldBe (1 to 2)
            keys.version(acme, KeyPurpose.Enc, 1).held().bytes() shouldBe first.bytes()
            (second.bytes() == first.bytes()) shouldBe false
            keys.current(acme, KeyPurpose.Bidx).held().version shouldBe 2
        }

        test("tenant A's transaction reads none of B's key rows") {
            val held = Fixture()
            held.keys.provision(acme).held()
            held.keys.provision(globex).held()

            val seen = held.tenants.transaction(TenantId("acme")) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select distinct tenant_id from surefold_tenant_keys").use { rows ->
                        generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                    }
                }
            }
            seen shouldBe listOf("acme")
        }

        test("the bound tenant's key is the one answered, and none is answered with no tenant bound") {
            val keys = Fixture().keys
            keys.provision(acme).held()

            withTenant(TenantId("acme")) { keys.current(KeyPurpose.Enc).held().owner } shouldBe acme
            keys.current(KeyPurpose.Enc) shouldBe Either.Left(KeyError.NoTenant)
        }

        test("a second provision changes nothing, and an owner not provisioned has nothing to rotate") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            val first = keys.current(acme, KeyPurpose.Enc).held()

            keys.provision(acme).held()

            keys.current(acme, KeyPurpose.Enc).held().bytes() shouldBe first.bytes()
            keys.rotate(globex).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }

        test("a retired version is gone, and the active one cannot be retired") {
            val keys = Fixture().keys
            keys.provision(acme).held()
            keys.rotate(acme).held()

            keys.retire(acme, KeyPurpose.Enc, 1).held()

            keys.version(acme, KeyPurpose.Enc, 1).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
            keys.retire(acme, KeyPurpose.Enc, 2).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
            keys.version(acme, KeyPurpose.Bidx, 1).held().version shouldBe 1
        }

        test("the platform's keys are kept beside the tenants', and out of every tenant's transaction") {
            val held = Fixture()
            held.keys.provision(KeyOwner.Platform).held()

            held.keys.current(KeyOwner.Platform, KeyPurpose.Enc).held().owner shouldBe KeyOwner.Platform
            held.tenants.transaction(TenantId("acme")) { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery("select count(*) from surefold_tenant_keys").use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            } shouldBe 0
        }

        test("a pooled connection a tenant transaction has used reads none of the platform's keys") {
            val held = Fixture()
            held.keys.provision(KeyOwner.Platform).held()
            held.database.app.connection.use { connection ->
                TenantTransactions(SingleConnection(connection)).transaction(TenantId("acme")) { }
                connection.createStatement().use { statement ->
                    statement.executeQuery("select current_setting('app.tenant_id', true)").use { rows ->
                        rows.next()
                        rows.getString(1)
                    }
                } shouldBe ""
                copied(connection).size shouldBe 0
            }
        }

        test("a rotation is current at once here, and notifies every other process once it commits") {
            val held = Fixture()
            held.keys.provision(acme).held()
            held.keys.current(acme, KeyPurpose.Enc).held().version shouldBe 1
            held.database.owner.connection.use { listening ->
                listening.createStatement().use { it.execute("listen ${TenantKeys.CHANNEL}") }

                held.keys.rotate(acme).held()

                held.keys.current(acme, KeyPurpose.Enc).held().version shouldBe 2
                val heard = listening.unwrap(PGConnection::class.java).getNotifications(10_000).orEmpty()
                heard.map { it.name to it.parameter } shouldBe listOf(TenantKeys.CHANNEL to "acme")
            }
        }

        test("after shred, the tenant's rows from a copy taken before it cannot be unwrapped") {
            val database = RlsDatabase(bookings = false).apply {
                apply("surefold/tenancy-crypto/postgres.sql")
                grantTo("surefold_tenant_keys")
            }
            val kms = InMemoryKms()
            val tenants = TenantTransactions(database.app, database.system)
            val keys = TenantKeys(tenants, kms)
            keys.provision(acme).held()
            keys.provision(globex).held()
            val copy = tenants.transaction(TenantId("acme")) { connection -> copied(connection) }

            keys.shred(acme, waiting = 30.days).held()

            copy.size shouldBe 2
            copy.forEach { row ->
                val purpose = KeyPurpose.entries.single { it.label == row.purpose }
                kms.decrypt(RootKey(row.root), row.wrapped, KeyContext(acme, purpose, row.version))
                    .shouldBeInstanceOf<Either.Left<KeyError.Denied>>()
            }
            keys.current(acme, KeyPurpose.Enc).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
            keys.current(globex, KeyPurpose.Enc).held().owner shouldBe globex
        }

        test("mid-rotation, any(candidates) finds a row indexed under each version, and two tenants index apart") {
            val held = Fixture()
            held.database.execute(
                "create table customers (tenant_id text not null default current_setting('app.tenant_id', true), " +
                    "email_bidx bytea not null)",
            )
            held.database.grantTo("customers")
            val index = BlindIndex(held.keys)
            val email = Field("customers", "email")
            fun stored(bidx: ByteArray) = held.tenants.transaction(TenantId("acme")) { connection ->
                connection.prepareStatement("insert into customers (email_bidx) values (?)").use {
                    it.setBytes(1, bidx)
                    it.executeUpdate()
                }
            }
            held.keys.provision(acme).held()
            held.keys.provision(globex).held()
            stored(index.of(acme, "jo@example.com", email).held())
            held.keys.rotate(acme).held()
            stored(index.of(acme, "jo@example.com", email).held())

            val found = held.tenants.transaction(TenantId("acme")) { connection ->
                connection.prepareStatement("select count(*) from customers where email_bidx = any(?)").use {
                    val candidates = index.candidates(acme, "jo@example.com", email).held()
                    it.setArray(1, connection.createArrayOf("bytea", candidates.toTypedArray()))
                    it.executeQuery().use { rows ->
                        rows.next()
                        rows.getInt(1)
                    }
                }
            }
            found shouldBe 2
            val theirs = index.of(globex, "jo@example.com", email).held().toList()
            index.candidates(acme, "jo@example.com", email).held().none { it.toList() == theirs } shouldBe true
        }

        test("a KMS that cannot be reached leaves no key row behind") {
            val database = RlsDatabase(bookings = false).apply {
                apply("surefold/tenancy-crypto/postgres.sql")
                grantTo("surefold_tenant_keys")
            }
            val kms = InMemoryKms().apply { unavailable = true }
            val keys = TenantKeys(TenantTransactions(database.app, database.system), kms)

            keys.provision(acme).shouldBeInstanceOf<Either.Left<KeyError.Unavailable>>()

            kms.unavailable = false
            keys.current(acme, KeyPurpose.Enc).shouldBeInstanceOf<Either.Left<KeyError.Invalid>>()
        }
    }
}
