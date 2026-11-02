package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.logAnnotated
import java.sql.Timestamp
import java.time.Instant

/** Enqueueing, cancelling and rescheduling in the caller's transaction (spec 0216). */
object JobsSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("a job enqueued in a transaction that rolls back leaves no row") {
            val source = jobsDatabase()
            source.connection.use { connection ->
                connection.autoCommit = false
                Jobs.enqueue(connection, Remind, "call sam", "sam") shouldBe true
                connection.rollback()
            }
            source.column("select count(*) from surefold_jobs") shouldBe listOf(0L)
        }

        test("the same key enqueued twice is one job, and the second answers false") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") } shouldBe true
            source.committed { Jobs.enqueue(it, Remind, "call sam again", "sam") } shouldBe false
            source.column("select payload->>'note' from surefold_jobs") shouldBe listOf("call sam")
        }

        test("a key deduplicates without a tenant, and not across tenants") {
            val source = jobsDatabase()
            source.committed { connection ->
                listOf(
                    Jobs.enqueue(connection, Remind, "a", "sam"),
                    Jobs.enqueue(connection, Remind, "b", "sam"),
                    Jobs.enqueue(connection, Remind, "c", "sam", tenant = "acme"),
                    Jobs.enqueue(connection, Remind, "d", "sam", tenant = "acme"),
                    Jobs.enqueue(connection, Remind, "e", "sam", tenant = "globex"),
                )
            } shouldContainExactly listOf(true, false, true, false, true)
        }

        test("a job is written as pending, at the clock's time, with its codec's version and nothing carried") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", priority = 3, maxAttempts = 7) }
            }
            source.column(
                "select concat_ws(' ', status, payload_version, priority, attempts, max_attempts, carried is null) " +
                    "from surefold_jobs",
            ) shouldBe listOf("pending 2 3 0 7 t")
            source.column("select run_at from surefold_jobs") shouldBe listOf(Timestamp.from(noon))
        }

        test("a job keeps what the enqueuing thread carried, quotes and all") {
            val source = jobsDatabase()
            logAnnotated("order" to "say \"hi\"") {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            }
            source.column("select carried->>'surefold.annotation.order' from surefold_jobs") shouldBe
                listOf("say \"hi\"")
        }

        test("a cancelled job moves to history as cancelled, and its key is free again") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
            clock.locally(TestClock(noon)) {
                source.committed { Jobs.cancel(it, Remind, "sam", tenant = "acme") }
            } shouldBe true
            source.column("select count(*) from surefold_jobs") shouldBe listOf(0L)
            source.column("select status from surefold_jobs_history") shouldBe listOf("cancelled")
            source.column("select finished_at from surefold_jobs_history") shouldBe listOf(Timestamp.from(noon))
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") } shouldBe true
        }

        test("cancelling a key nothing waits under answers false, and touches no other tenant's job") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
            source.committed { Jobs.cancel(it, Remind, "sam") } shouldBe false
            source.committed { Jobs.cancel(it, Remind, "sam", tenant = "globex") } shouldBe false
            source.committed { Jobs.cancel(it, Remind, "nobody", tenant = "acme") } shouldBe false
            source.column("select count(*) from surefold_jobs") shouldBe listOf(1L)
        }

        test("a cancel in a transaction that rolls back leaves the job waiting") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            source.connection.use { connection ->
                connection.autoCommit = false
                Jobs.cancel(connection, Remind, "sam") shouldBe true
                connection.rollback()
            }
            source.column("select status from surefold_jobs") shouldBe listOf("pending")
            source.column("select count(*) from surefold_jobs_history") shouldBe listOf(0L)
        }

        test("a rescheduled job runs at its new time, and a key nothing waits under answers false") {
            val source = jobsDatabase()
            val later = noon.plusSeconds(3_600)
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", runAt = noon) }
            source.committed { Jobs.reschedule(it, Remind, "sam", later) } shouldBe true
            source.committed { Jobs.reschedule(it, Remind, "sam", later, tenant = "acme") } shouldBe false
            source.column("select run_at from surefold_jobs") shouldBe listOf(Timestamp.from(later))
        }

        test("a running job is neither cancelled nor rescheduled") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", runAt = noon) }
            source.committed { connection ->
                connection.createStatement().use { it.executeUpdate("update surefold_jobs set status = 'running'") }
            }
            source.committed { Jobs.cancel(it, Remind, "sam") } shouldBe false
            source.committed { Jobs.reschedule(it, Remind, "sam", noon.plusSeconds(60)) } shouldBe false
            source.column("select run_at from surefold_jobs") shouldBe listOf(Timestamp.from(noon))
        }
    }
}
