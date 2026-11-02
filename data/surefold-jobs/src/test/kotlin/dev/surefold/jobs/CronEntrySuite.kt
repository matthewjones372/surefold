package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** System cron entries, enqueued by every worker and run once per firing (spec 0218). */
object CronEntrySuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("two workers on one clock across an hour of a five-minute entry run it 12 times, not 24") {
            val source = jobsDatabase()
            val runs = AtomicInteger()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done.also { runs.incrementAndGet() } })
            val entry = CronEntry("remind", Cron.parse("*/5 * * * *", UTC), Remind, "check in")
            val time = TestClock(noon)
            val settings = JobSettings(housekeeping = 1.minutes)
            clock.locally(time) {
                val workers = listOf("a", "b").map { name ->
                    JobWorker(source, handlers, settings, name = name, cron = listOf(entry))
                }
                repeat(60) {
                    workers.forEach { worker -> worker.enqueueCron() }
                    workers.forEach { worker -> worker.claimAndRun() }
                    // Runs are asynchronous; one still going as the clock jumps would miss its deadline on it.
                    source.awaitNoneRunning()
                    time.adjust(1.minutes)
                }
                workers.forEach { it.claimAndRun() }
                workers.forEach { it.close() }
            }
            runs.get() shouldBe 12
            source.column("select count(*) from surefold_jobs_history where status = 'done'") shouldBe listOf(12L)
        }

        test("each firing is keyed by the entry's name and its instant, and enqueued ahead by the window") {
            val source = jobsDatabase()
            val entry = CronEntry("digest", Cron.parse("0 * * * *", UTC), Remind, "send digest")
            clock.locally(TestClock(noon.plusSeconds(1))) { entry.enqueueAhead(source, 150.minutes) } shouldBe 2
            source.column("select dedupe_key from surefold_jobs order by run_at") shouldContainExactly listOf(
                "cron:digest:2026-10-26T13:00:00Z",
                "cron:digest:2026-10-26T14:00:00Z",
            )
            clock.locally(TestClock(noon.plusSeconds(1))) { entry.enqueueAhead(source, 150.minutes) } shouldBe 0
        }

        test("an entry whose cron fires after the window enqueues nothing yet") {
            val source = jobsDatabase()
            val entry = CronEntry("yearly", Cron.parse("0 0 1 1 *", UTC), Remind, "new year")
            clock.locally(TestClock(noon)) { entry.enqueueAhead(source, 2.minutes) } shouldBe 0
            entry.toString() shouldBe "CronEntry(yearly, 0 0 1 1 * (Z))"
        }

        test("an entry needs a name") {
            shouldThrow<IllegalArgumentException> { CronEntry(" ", Cron.parse("* * * * *", UTC), Remind, "x") }
        }
    }

    private fun DataSource.awaitNoneRunning() {
        while (column("select count(*) from surefold_jobs where status = 'running'").single() != 0L) Thread.sleep(5)
    }
}
