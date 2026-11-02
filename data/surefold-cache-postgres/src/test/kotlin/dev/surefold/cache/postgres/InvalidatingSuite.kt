package dev.surefold.cache.postgres

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.postgres.notify.Notice
import dev.surefold.postgres.notify.PostgresNotifications
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.of
import io.github.matthewjones372.surefold.stream.runCollect
import io.github.matthewjones372.surefold.stream.start
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/** A cache told by Postgres that a row changed, before its ttl runs out (spec 0246). */
object InvalidatingSuite : Suite() {

    private fun database(): DataSource = PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) }

    private fun listening(source: DataSource, channel: String): Int = source.connection.use { connection ->
        connection.prepareStatement(
            "select count(*) from pg_stat_activity where datname = current_database() and state = 'idle' and query = ?",
        ).use { statement ->
            statement.setString(1, "listen $channel")
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    init {
        test("a payload is the key it names, and a gap is everything") {
            val run = Stream.of(Notice.Received("prices", "sku-1", 1), Notice.Gap)
                .invalidations { it }
                .runCollect()
                .start(Forks())
            (run.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) as Exit.Done).value shouldBe
                listOf(Invalidation.Key("sku-1"), Invalidation.All)
        }

        test("a NOTIFY from another connection drops its key before its ttl, and only that key") {
            val source = database()
            val lookups = AtomicInteger()
            val prices = Cache<String, Nothing, Int>({ 1.hours }) { Either.Right(lookups.incrementAndGet()) }
            val running = PostgresNotifications.listen(source, setOf("prices")).invalidating(prices) { it }
                .start(Forks())
            eventually(within = 10.seconds) { listening(source, "prices") shouldBe 1 }

            prices.get("sku-1") shouldBe Either.Right(1)
            prices.get("sku-2") shouldBe Either.Right(2)
            source.connection.use { connection -> PostgresNotifications.notify(connection, "prices", "sku-1") }

            eventually(within = 10.seconds) { prices.get("sku-1") shouldBe Either.Right(3) }
            prices.get("sku-2") shouldBe Either.Right(2)
            running.close()
            prices.close()
        }
    }
}
