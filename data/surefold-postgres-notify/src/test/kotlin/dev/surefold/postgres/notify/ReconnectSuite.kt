package dev.surefold.postgres.notify

import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.runCollect
import dev.surefold.stream.start
import dev.surefold.stream.take
import dev.surefold.test.eventually
import org.postgresql.ds.PGSimpleDataSource
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** A lost connection made again on the schedule, and a Gap before what it hears next (spec 0238). */
object ReconnectSuite : Suite() {

    private fun database(): PGSimpleDataSource = PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) }

    /** [select] over the connections to [source]'s database that have finished a `LISTEN` on [last]. */
    private fun listeners(source: DataSource, last: String, select: String): Int = source.connection.use { connection ->
        connection.prepareStatement(
            "select $select from pg_stat_activity " +
                "where datname = current_database() and state = 'idle' and query = ?",
        ).use { statement ->
            statement.setString(1, "listen $last")
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    private fun listening(source: DataSource, last: String): Int = listeners(source, last, "count(*)")

    /** Ends the connection listening on [last], as a failover or an idle timeout would. */
    private fun terminated(source: DataSource, last: String) {
        listeners(source, last, "count(pg_terminate_backend(pid))") shouldBe 1
    }

    private fun DataSource.sent(channel: String, payload: String) {
        connection.use { PostgresNotifications.notify(it, channel, payload) }
    }

    /** [source], whose first [failing] connections are refused, as a database still starting refuses them. */
    private class Refusing(private val source: DataSource, failing: Int) : DataSource by source {
        private val left = AtomicInteger(failing)

        override fun getConnection(): Connection {
            if (left.getAndDecrement() > 0) throw SQLException("the database system is starting up")
            return source.connection
        }
    }

    init {
        test("a lost connection waits out its delay, listens again, and says Gap before the next notification") {
            val source = database()
            val time = TestClock()
            val running = clock.locally(time) {
                PostgresNotifications.listen(source, setOf("bookings"), reconnect = Schedule.spaced(10.seconds))
            }.take(2).runCollect().start(Forks())
            eventually(within = 10.seconds) { listening(source, "bookings") shouldBe 1 }

            terminated(source, "bookings")
            eventually(within = 10.seconds) { time.sleepers() shouldBe 1 }
            listening(source, "bookings") shouldBe 0

            time.adjust(10.seconds)
            eventually(within = 10.seconds) { listening(source, "bookings") shouldBe 1 }
            source.sent("bookings", "after")

            val heard = (running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) as Exit.Done).value
            heard.first() shouldBe Notice.Gap
            (heard.last() as Notice.Received).payload shouldBe "after"
        }

        test("a first connection that cannot be made is tried again on the schedule, with no Gap") {
            val source = database()
            val time = TestClock()
            val refusing = Refusing(source, failing = 1)
            val running = clock.locally(time) {
                PostgresNotifications.listen(refusing, setOf("bookings"), reconnect = Schedule.spaced(5.seconds))
            }.take(1).runCollect().start(Forks())
            eventually(within = 10.seconds) { time.sleepers() shouldBe 1 }

            time.adjust(5.seconds)
            eventually(within = 10.seconds) { listening(source, "bookings") shouldBe 1 }
            source.sent("bookings", "first")

            val heard = (running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) as Exit.Done).value
            heard.map { (it as Notice.Received).payload } shouldBe listOf("first")
        }

        test("a schedule that stops ends the run with the failure") {
            val source = database()
            val running = PostgresNotifications.listen(source, setOf("bookings"), reconnect = Schedule.recurs(0))
                .runCollect()
                .start(Forks())
            eventually(within = 10.seconds) { listening(source, "bookings") shouldBe 1 }

            terminated(source, "bookings")

            running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS).shouldBeInstanceOf<Exit.Died>()
        }

        test("a run stopped while it waits to reconnect stops at once") {
            val source = database()
            val time = TestClock()
            val refusing = Refusing(source, failing = 1)
            val running = clock.locally(time) {
                PostgresNotifications.listen(refusing, setOf("bookings"), reconnect = Schedule.spaced(1.seconds))
            }.runCollect().start(Forks())
            eventually(within = 10.seconds) { time.sleepers() shouldBe 1 }

            running.stop()

            running.exit.toCompletableFuture().get(2, TimeUnit.SECONDS) shouldBe Exit.Done(emptyList())
        }
    }
}
