package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import java.sql.Timestamp
import java.time.Instant
import java.time.ZoneId
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** A series on a rule, each occurrence enqueuing the next as it finishes (spec 0218). */
object RecurrenceSuite : Suite() {
    private val london = ZoneId.of("Europe/London")

    init {
        test("a daily 08:00 Europe/London rule fires at 07:00 UTC, then 08:00 UTC across the October change") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done })
            val time = TestClock(Instant.parse("2026-10-24T00:00:00Z"))
            clock.locally(time) {
                source.committed { connection ->
                    Jobs.enqueueRecurring(connection, Remind, "digest", "digest", Cron.parse("0 8 * * *", london))
                }
                repeat(3) {
                    time.setTime(source.nextRunAt())
                    source.runDue(handlers)
                }
            }
            source.column("select run_at from surefold_jobs_history order by run_at")
                .map { (it as Timestamp).toInstant().toString() } shouldContainExactly listOf(
                "2026-10-24T07:00:00Z",
                "2026-10-25T08:00:00Z",
                "2026-10-26T08:00:00Z",
            )
            source.column("select dedupe_key || ' ' || recurrence from surefold_jobs") shouldBe
                listOf("digest 0 8 * * * Europe/London")
        }

        test("a failed or skipped occurrence still enqueues the next, and a retry does not") {
            val source = jobsDatabase()
            val results = ArrayDeque(listOf<JobResult>(JobResult.Fail("down"), JobResult.Retry(1.minutes, "busy")))
            val handlers = JobHandlers.of(Remind.handledBy { results.removeFirst() })
            val time = TestClock(Instant.parse("2026-10-26T00:00:00Z"))
            clock.locally(time) {
                source.committed { connection ->
                    Jobs.enqueueRecurring(connection, Remind, "hourly", "hourly", Cron.parse("0 * * * *", london))
                }
                time.setTime(source.nextRunAt())
                source.runDue(handlers)
                source.column("select status from surefold_jobs_history") shouldBe listOf("failed")
                time.setTime(source.nextRunAt())
                source.runDue(handlers)
            }
            source.column("select count(*) from surefold_jobs") shouldBe listOf(1L)
            source.column("select count(*) from surefold_jobs_history") shouldBe listOf(1L)
        }

        test("an occurrence run late enqueues the next firing after now, not the ones it missed") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done })
            val time = TestClock(Instant.parse("2026-10-26T00:00:00Z"))
            clock.locally(time) {
                source.committed { connection ->
                    Jobs.enqueueRecurring(connection, Remind, "hourly", "hourly", Cron.parse("0 * * * *", london))
                }
                time.setTime(Instant.parse("2026-10-26T05:30:00Z"))
                source.runDue(handlers)
            }
            source.nextRunAt().toString() shouldBe "2026-10-26T06:00:00Z"
        }

        test("cancelling a series' waiting occurrence ends it") {
            val source = jobsDatabase()
            clock.locally(TestClock(Instant.parse("2026-10-26T00:00:00Z"))) {
                source.committed { connection ->
                    Jobs.enqueueRecurring(connection, Remind, "hourly", "hourly", Cron.parse("0 * * * *", london))
                }
                source.committed { Jobs.cancel(it, Remind, "hourly") } shouldBe true
            }
            source.column("select count(*) from surefold_jobs") shouldBe listOf(0L)
        }
    }

    private fun DataSource.nextRunAt(): Instant =
        (column("select run_at from surefold_jobs where status = 'pending'").single() as Timestamp).toInstant()

    private fun DataSource.runDue(handlers: JobHandlers) {
        val queue = Queue(this, "a")
        queue.claim(limit = 10, lease = 1.minutes).forEach { JobRun(queue, handlers).run(it) }
    }
}
