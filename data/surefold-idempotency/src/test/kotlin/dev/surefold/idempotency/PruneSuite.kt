package dev.surefold.idempotency

import dev.surefold.ByteChunk
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.jobs.Cron
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
import dev.surefold.jobs.Jobs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate
import java.time.Instant
import java.time.ZoneOffset.UTC
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Expired keys deleted by a surefold-jobs worker, and live ones kept (spec 0226). */
object PruneSuite : Suite() {
    private val response = StoredResponse(200, emptyList(), ByteChunk.empty())

    init {
        test("a prune deletes expired keys, and keeps live ones and one still running on a live lease") {
            val source = database()
            val store = PostgresIdempotency(source, lease = 3.hours)
            val time = TestClock(Instant.parse("2026-10-26T12:00:00Z"))
            clock.locally(time) {
                store.done(IdempotencyKey("s", "old"), ttl = 1.hours)
                store.done(IdempotencyKey("s", "live"), ttl = 48.hours)
                store.reserve(IdempotencyKey("s", "running"), "f", ttl = 1.seconds)
                time.adjust(2.hours)
                store.prune() shouldBe 1
            }
            source.keys() shouldBe listOf("live", "running")
        }

        test("a worker given the prune kind deletes expired keys") {
            val source = database()
            val store = PostgresIdempotency(source)
            store.done(IdempotencyKey("s", "old"), ttl = 1.milliseconds)
            Thread.sleep(5)
            source.connection.use { Jobs.enqueue(it, IdempotencyPrune, Unit, "now") }
            JobWorker(source, JobHandlers.of(IdempotencyPrune.handledBy(store)), JobSettings(poll = 20.milliseconds))
                .start().use { _ -> eventually(within = 30.seconds) { source.keys() shouldBe emptyList() } }
        }

        test("the cron entry prunes as the prune kind, under its name") {
            val entry = IdempotencyPrune.every(Cron.parse("0 * * * *", UTC))
            entry.kind shouldBe IdempotencyPrune
            entry.name shouldBe "surefold-idempotency-prune"
        }
    }

    private fun PostgresIdempotency.done(key: IdempotencyKey, ttl: Duration) {
        val token = (reserve(key, "f", ttl) as Reservation.Reserved).token
        complete(key, token, response)
    }

    private fun DataSource.keys(): List<String> = connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("select key from surefold_idempotency order by key").use { rows ->
                generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
            }
        }
    }

    private fun database(): DataSource = Postgres.empty().also { source ->
        for (changelog in listOf("surefold/idempotency/postgres.sql", "surefold/jobs/postgres.sql")) {
            source.migrate(changelog)
        }
    }
}
