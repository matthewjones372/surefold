package io.github.matthewjones372.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldHaveSize
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.seconds

/** Claiming with a lease, finishing into history, sweeping and pruning (spec 0216). */
object QueueSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("two workers claiming 1,000 jobs at once never claim the same one") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    (1..1_000).forEach { Jobs.enqueue(connection, Remind, "$it", "$it") }
                }
                val claimed = ConcurrentLinkedQueue<UUID>()
                val workers = listOf("a", "b").map { name ->
                    val queue = Queue(source, name)
                    Thread.ofVirtual().start {
                        clock.locally(TestClock(noon)) {
                            while (true) {
                                val batch = queue.claim(limit = 25, lease = 30.seconds)
                                if (batch.isEmpty()) break
                                claimed += batch.map { it.id }
                            }
                        }
                    }
                }
                workers.forEach { it.join() }
                claimed.size shouldBe 1_000
                claimed.toSet().size shouldBe 1_000
            }
            source.column("select distinct status || ' ' || attempts from surefold_jobs") shouldBe listOf("running 1")
        }

        test("a claim takes the highest priority first, then the earliest, and nothing not yet due") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    Jobs.enqueue(connection, Remind, "late", "late", runAt = noon.minusSeconds(10))
                    Jobs.enqueue(connection, Remind, "early", "early", runAt = noon.minusSeconds(20))
                    Jobs.enqueue(connection, Remind, "urgent", "urgent", runAt = noon, priority = 9)
                    Jobs.enqueue(connection, Remind, "future", "future", runAt = noon.plusSeconds(1), priority = 99)
                }
                Queue(source, "a").claim(limit = 10, lease = 30.seconds)
                    .map { Notes.decode(it.payloadVersion, it.payload) }
            } shouldContainExactly listOf("urgent", "early", "late")
        }

        test("a crashed worker's lease is claimed again once it passes, and the crashed run's finish changes nothing") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                val crashed = Queue(source, "a").claim(limit = 1, lease = 30.seconds).single()
                val other = Queue(source, "b")

                time.adjust(29.seconds)
                other.sweep() shouldBe 0
                other.claim(limit = 1, lease = 30.seconds).shouldBeEmpty()

                time.adjust(2.seconds)
                other.sweep() shouldBe 1
                val again = other.claim(limit = 1, lease = 30.seconds).single()
                again.id shouldBe crashed.id
                again.attempts shouldBe 2

                Queue(source, "a").finish(crashed, Finished.Done) shouldBe false
                other.finish(again, Finished.Done) shouldBe true
            }
            source.column("select status || ' ' || attempts from surefold_jobs_history") shouldBe listOf("done 2")
        }

        test("a lease that passes on a job's last attempt fails it into history") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", maxAttempts = 1) }
                Queue(source, "a").claim(limit = 1, lease = 30.seconds) shouldHaveSize 1
                time.adjust(31.seconds)
                Queue(source, "b").sweep() shouldBe 0
            }
            source.column("select count(*) from surefold_jobs") shouldBe listOf(0L)
            source.column("select status || ': ' || last_error from surefold_jobs_history") shouldBe
                listOf("failed: its lease expired on its last attempt")
        }

        test("a finished job moves to history with its error, and frees its key") {
            val source = jobsDatabase()
            clock.locally(TestClock(noon)) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
                val queue = Queue(source, "a")
                queue.finish(queue.claim(limit = 1, lease = 30.seconds).single(), Finished.Skipped, "too late")
            } shouldBe true
            source.column("select status || ': ' || last_error || ' by ' || locked_by from surefold_jobs_history")
                .shouldBe(listOf("skipped: too late by a"))
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") } shouldBe true
        }

        test("history older than its retention is pruned, and newer history is kept") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                val queue = Queue(source, "a")
                queue.finish(queue.claim(limit = 1, lease = 30.seconds).single(), Finished.Done)
                time.adjust(6.days)
                queue.prune(retention = 7.days) shouldBe 0
                time.adjust(2.days)
                queue.prune(retention = 7.days) shouldBe 1
            }
            source.column("select count(*) from surefold_jobs_history") shouldBe listOf(0L)
        }
    }
}
