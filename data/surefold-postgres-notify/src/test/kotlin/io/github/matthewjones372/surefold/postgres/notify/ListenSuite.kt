package io.github.matthewjones372.surefold.postgres.notify

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.runCollect
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.postgresql.ds.PGSimpleDataSource
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

/** Notifications heard on a connection of their own, in commit order, and only once committed (spec 0238). */
object ListenSuite : Suite() {

    /** A fresh database, reached without a pool, as a listener's own source is. */
    private fun database(): DataSource = PGSimpleDataSource().apply { setUrl(Postgres.emptyUrl()) }

    /**
     * How many connections to [source]'s database have finished a `LISTEN` on [last], and so on every channel
     * before it: a run listens on its channels one statement at a time, in order.
     */
    private fun listening(source: DataSource, last: String): Int = source.connection.use { connection ->
        connection.prepareStatement(
            "select count(*) from pg_stat_activity where datname = current_database() and state = 'idle' and query = ?",
        ).use { statement ->
            statement.setString(1, "listen $last")
            statement.executeQuery().use { rows ->
                rows.next()
                rows.getInt(1)
            }
        }
    }

    /** [payload] sent on [channel] in a transaction of its own, which commits or, with [commit] false, rolls back. */
    private fun DataSource.sent(channel: String, payload: String, commit: Boolean = true) {
        connection.use { connection ->
            connection.autoCommit = false
            PostgresNotifications.notify(connection, channel, payload)
            if (commit) connection.commit() else connection.rollback()
        }
    }

    init {
        test("what commits arrives once, in commit order, on each channel; what rolls back never does") {
            val source = database()
            val running = PostgresNotifications.listen(source, setOf("bookings", "courts"))
                .take(3)
                .runCollect()
                .start(Forks())
            eventually(within = 10.seconds) { listening(source, last = "courts") shouldBe 1 }

            source.sent("bookings", "1")
            source.sent("bookings", "lost", commit = false)
            source.sent("courts", "2")
            source.sent("bookings", "3")

            val heard = (running.exit.toCompletableFuture().get(10, TimeUnit.SECONDS) as Exit.Done).value
            heard.map { (it as Notice.Received).channel to it.payload } shouldBe
                listOf("bookings" to "1", "courts" to "2", "bookings" to "3")
        }

        test("stopping the run gives its connection back") {
            val source = database()
            val running = PostgresNotifications.listen(source, setOf("bookings")).runCollect().start(Forks())
            eventually(within = 10.seconds) { listening(source, last = "bookings") shouldBe 1 }

            running.close()

            eventually(within = 10.seconds) { listening(source, last = "bookings") shouldBe 0 }
        }

        test("a channel that is not a lower-case identifier is refused before any connection is taken") {
            val nowhere = PGSimpleDataSource()
            shouldThrow<IllegalArgumentException> { PostgresNotifications.listen(nowhere, setOf("Bookings")) }
            shouldThrow<IllegalArgumentException> { PostgresNotifications.listen(nowhere, setOf("a; drop")) }
            shouldThrow<IllegalArgumentException> { PostgresNotifications.listen(nowhere, emptySet()) }
        }

        test("a payload past 7,999 bytes is refused, and one at it is sent") {
            val source = database()
            source.sent("bookings", "x".repeat(7_999))
            shouldThrow<IllegalArgumentException> { source.sent("bookings", "x".repeat(8_000)) }
        }
    }
}
