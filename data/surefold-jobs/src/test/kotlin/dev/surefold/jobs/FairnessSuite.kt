package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** A claim that takes at most so many of one tenant's jobs (spec 0217). */
object FairnessSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("a tenant with five jobs behind another's hundred gets all five in the first claim") {
            val source = jobsDatabase()
            val claimed = clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    (1..100).forEach { n ->
                        val runAt = noon.minusSeconds(200L - n)
                        Jobs.enqueue(connection, Remind, "a$n", "a$n", runAt = runAt, tenant = "a")
                    }
                    (1..5).forEach { Jobs.enqueue(connection, Remind, "b$it", "b$it", tenant = "b") }
                }
                Queue(source, "w").claim(limit = 10, lease = 1.minutes, perTenant = 5)
            }
            claimed.groupingBy { it.tenant }.eachCount() shouldBe mapOf("a" to 5, "b" to 5)
        }

        test("within a tenant, a fair claim still takes the highest priority, then the earliest") {
            val source = jobsDatabase()
            val claimed = clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    Jobs.enqueue(connection, Remind, "late", "late", runAt = noon.minusSeconds(10), tenant = "a")
                    Jobs.enqueue(connection, Remind, "early", "early", runAt = noon.minusSeconds(20), tenant = "a")
                    Jobs.enqueue(connection, Remind, "urgent", "urgent", priority = 9, tenant = "a")
                }
                Queue(source, "w").claim(limit = 10, lease = 1.minutes, perTenant = 2)
            }
            claimed.map { Notes.decode(it.payloadVersion, it.payload) } shouldContainExactly listOf("urgent", "early")
        }

        test("jobs with no tenant share one tenant's cap") {
            val source = jobsDatabase()
            val claimed = clock.locally(TestClock(noon)) {
                source.committed { connection -> (1..6).forEach { Jobs.enqueue(connection, Remind, "$it", "$it") } }
                Queue(source, "w").claim(limit = 10, lease = 1.minutes, perTenant = 4)
            }
            claimed.size shouldBe 4
        }

        test("two workers claiming fairly at once never claim the same job") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    (1..200).forEach { Jobs.enqueue(connection, Remind, "$it", "$it", tenant = "t${it % 7}") }
                }
            }
            val claimed = ConcurrentLinkedQueue<UUID>()
            listOf("a", "b").map { name ->
                Thread.ofVirtual().start {
                    clock.locally(TestClock(noon)) {
                        val queue = Queue(source, name)
                        while (true) {
                            val batch = queue.claim(limit = 10, lease = 1.minutes, perTenant = 2)
                            claimed += batch.map { it.id }
                            // Every top candidate can be the other worker's for a moment, so empty is not done.
                            if (batch.isEmpty() && source.pending() == 0L) break
                        }
                    }
                }
            }.forEach { it.join() }
            claimed.size shouldBe 200
            claimed.toSet().size shouldBe 200
        }
    }

    private fun DataSource.pending() =
        column("select count(*) from surefold_jobs where status = 'pending'").single() as Long
}
