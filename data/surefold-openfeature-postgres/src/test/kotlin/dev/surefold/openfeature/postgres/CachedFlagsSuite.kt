package dev.surefold.openfeature.postgres

import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.ProviderState
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import dev.surefold.test.eventually
import java.lang.reflect.Proxy
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** A scope's rows kept for the ttl, changed at once by set and clear, and served stale while Postgres is down. */
object CachedFlagsSuite : Suite() {
    private val acme = TenantId("acme")
    private val nobody = ImmutableContext()

    private fun database(): RlsDatabase = RlsDatabase(bookings = false).also { database ->
        database.apply("surefold/openfeature-postgres/postgres.sql")
        database.grantTo("surefold_feature_flags")
    }

    private fun RlsDatabase.row(key: String, value: String) = execute(
        "insert into surefold_feature_flags values ('$key', 'acme', '$value', 'a test', 'ops:test', now()) " +
            "on conflict (key, (coalesce(tenant_id, ''))) do update set value = excluded.value",
    )

    private fun PostgresFlags.limit(): Int = withTenant(acme) { getIntegerEvaluation("limit", 0, nobody) }.value

    init {
        test("a row changed by another process is seen once the ttl has run out, and not before") {
            val database = database()
            database.row("limit", "5")
            val moving = TestClock()
            val flags = clock.locally(moving) { PostgresFlags(database.app, ttl = 30.seconds) }

            flags.limit() shouldBe 5
            database.row("limit", "6")
            moving.adjust(29.seconds)
            flags.limit() shouldBe 5
            moving.adjust(2.seconds)
            flags.limit() shouldBe 6
            flags.shutdown()
        }

        test("a row set or cleared in this process is seen at once") {
            val database = database()
            database.execute(
                "insert into surefold_feature_flags values ('limit', null, '10', 'a test', 'ops:test', now())",
            )
            val flags = clock.locally(TestClock()) { PostgresFlags(database.app, ttl = 30.minutes) }
            flags.limit() shouldBe 10

            database.system.connection.use { flags.set(it, "limit", acme, "5", "a pilot", "ops:sam") }
            flags.limit() shouldBe 5
            database.system.connection.use { flags.set(it, "limit", acme, "7", "a bigger pilot", "ops:sam") }
            flags.limit() shouldBe 7
            database.system.connection.use { flags.clear(it, "limit", acme) }
            flags.limit() shouldBe 10
            database.system.connection.use { flags.set(it, "limit", null, "12", "for everyone", "ops:sam") }
            flags.limit() shouldBe 12
            flags.shutdown()
        }

        test("while Postgres is down the cached rows answer, as STALE, until stale runs out, and then ERROR") {
            val database = database()
            database.row("limit", "5")
            val down = AtomicBoolean(false)
            val moving = TestClock()
            val flags = clock.locally(moving) {
                PostgresFlags(failing(database.app, down), ttl = 30.seconds, stale = 10.minutes)
            }
            val api = OpenFeatureAPI.getInstance()
            api.setProviderAndWait("cached-flags", flags)
            val client = api.getClient("cached-flags")

            flags.limit() shouldBe 5
            down.set(true)
            moving.adjust(31.seconds)
            flags.limit() shouldBe 5
            eventually(within = 10.seconds) { client.providerState shouldBe ProviderState.STALE }

            moving.adjust(10.minutes)
            flags.limit() shouldBe 0
            eventually(within = 10.seconds) { client.providerState shouldBe ProviderState.ERROR }

            down.set(false)
            moving.adjust(2.seconds)
            flags.limit() shouldBe 5
            eventually(within = 10.seconds) { client.providerState shouldBe ProviderState.READY }
            api.shutdown()
        }
    }

    // [source], or a refusal to connect while [down] is set, as a database that cannot be reached gives.
    private fun failing(source: DataSource, down: AtomicBoolean): DataSource =
        Proxy.newProxyInstance(DataSource::class.java.classLoader, arrayOf(DataSource::class.java)) { _, method, args ->
            if (method.name == "getConnection" && down.get()) throw SQLException("the database is down")
            method.invoke(source, *(args ?: emptyArray()))
        } as DataSource
}
