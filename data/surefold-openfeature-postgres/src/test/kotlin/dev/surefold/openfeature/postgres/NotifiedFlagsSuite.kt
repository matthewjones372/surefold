package dev.surefold.openfeature.postgres

import dev.openfeature.sdk.ImmutableContext
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.postgres.notify.Notice
import dev.surefold.postgres.notify.PostgresNotifications
import dev.surefold.tenancy.RlsDatabase
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.withTenant
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.of
import io.github.matthewjones372.surefold.stream.start
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A change to any row, by any writer, seen before the ttl, by the tenants it is for (spec 0240). */
object NotifiedFlagsSuite : Suite() {
    private val acme = TenantId("acme")
    private val globex = TenantId("globex")
    private val nobody = ImmutableContext()

    private fun database(): RlsDatabase = RlsDatabase(bookings = false).also { database ->
        database.apply("surefold/openfeature-postgres/postgres.sql")
        database.grantTo("surefold_feature_flags")
        database.execute(
            "insert into surefold_feature_flags values " +
                "('limit', 'acme', '5', 'a test', 'ops:test', now()), " +
                "('limit', null, '10', 'a test', 'ops:test', now())",
        )
    }

    private fun PostgresFlags.limit(tenant: TenantId): Int =
        withTenant(tenant) { getIntegerEvaluation("limit", 0, nobody) }.value

    // How many connections are waiting on the flags channel: the listener is ready once it is one.
    private fun listeners(source: DataSource): Int = source.connection.use { connection ->
        connection.prepareStatement(
            "select count(*) from pg_stat_activity where datname = current_database() and state = 'idle' and query = ?",
        ).use { statement ->
            statement.setString(1, "listen $FLAGS_CHANNEL")
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    init {
        test("a tenant's notice drops one scope, an empty one or a gap drops all, and another channel's nothing") {
            val flags = PostgresFlags(PGSimpleDataSource())
            val run = flags.listening(
                Stream.of(
                    Notice.Received(FLAGS_CHANNEL, "acme", 1),
                    Notice.Received(FLAGS_CHANNEL, "", 1),
                    Notice.Received("prices", "acme", 1),
                    Notice.Gap,
                ),
            ).start(Forks())
            (run.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) as Exit.Done).value shouldBe 3L
            flags.shutdown()
        }

        test("a tenant row changed by plain SQL is seen before the ttl, by that tenant only") {
            val database = database()
            val flags = PostgresFlags(database.app, ttl = 1.hours)
            val running = flags.listening(PostgresNotifications.listen(database.owner, setOf(FLAGS_CHANNEL)))
                .start(Forks())
            eventually(within = 10.seconds) { listeners(database.owner) shouldBe 1 }
            flags.limit(acme) shouldBe 5
            flags.limit(globex) shouldBe 10

            // The null row changes unheard, so only a scope that was dropped can see it.
            database.execute(
                "alter table surefold_feature_flags disable trigger surefold_feature_flags_notify; " +
                    "update surefold_feature_flags set value = '11' where tenant_id is null; " +
                    "alter table surefold_feature_flags enable trigger surefold_feature_flags_notify",
            )
            database.execute("update surefold_feature_flags set value = '6' where tenant_id = 'acme'")

            eventually(within = 10.seconds) { flags.limit(acme) shouldBe 6 }
            flags.limit(globex) shouldBe 10
            running.close()
            flags.shutdown()
        }

        test("a change to the row every tenant gets is seen by every tenant before the ttl") {
            val database = database()
            val flags = PostgresFlags(database.app, ttl = 1.hours)
            val running = flags.listening(PostgresNotifications.listen(database.owner, setOf(FLAGS_CHANNEL)))
                .start(Forks())
            eventually(within = 10.seconds) { listeners(database.owner) shouldBe 1 }
            flags.limit(globex) shouldBe 10
            withTenant(acme) { flags.getIntegerEvaluation("retries", 0, nobody) }.value shouldBe 0

            database.execute(
                "insert into surefold_feature_flags values ('retries', null, '3', 'a test', 'ops:test', now())",
            )
            database.execute("update surefold_feature_flags set value = '12' where tenant_id is null and key = 'limit'")

            eventually(within = 10.seconds) { flags.limit(globex) shouldBe 12 }
            withTenant(acme) { flags.getIntegerEvaluation("retries", 0, nobody) }.value shouldBe 3
            running.close()
            flags.shutdown()
        }

        test("a gap drops every scope, since a change may have been missed") {
            val database = database()
            val flags = PostgresFlags(database.app, ttl = 1.hours)
            flags.limit(globex) shouldBe 10
            database.execute(
                "alter table surefold_feature_flags disable trigger surefold_feature_flags_notify; " +
                    "update surefold_feature_flags set value = '11' where tenant_id is null",
            )

            val run = flags.listening(Stream.of(Notice.Gap)).start(Forks())
            run.exit.toCompletableFuture().get(10, TimeUnit.SECONDS)
            flags.limit(globex) shouldBe 11
            flags.shutdown()
        }
    }
}
