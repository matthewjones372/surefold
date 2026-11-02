package io.github.matthewjones372.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.minutes

/** A job enqueued to run now wakes a worker between polls (spec 0217). */
object NotifySuite : Suite() {
    init {
        test("with a poll of a minute, a job enqueued to run now runs within a second") {
            val source = jobsDatabase()
            val ran = CountDownLatch(1)
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done.also { ran.countDown() } })
            JobWorker(source, handlers, JobSettings(poll = 1.minutes)).start().use { _ ->
                // The worker's first poll and its LISTEN are done well before this.
                Thread.sleep(1_000)
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                ran.await(1, TimeUnit.SECONDS) shouldBe true
            }
        }

        test("an enqueue that rolls back wakes nothing, and one for later waits for its poll") {
            val source = jobsDatabase()
            val ran = CountDownLatch(1)
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done.also { ran.countDown() } })
            JobWorker(source, handlers, JobSettings(poll = 1.minutes)).start().use { _ ->
                Thread.sleep(1_000)
                source.connection.use { connection ->
                    connection.autoCommit = false
                    Jobs.enqueue(connection, Remind, "call sam", "sam")
                    connection.rollback()
                }
                source.committed {
                    Jobs.enqueue(it, Remind, "call ana", "ana", runAt = java.time.Instant.now().plusMillis(200))
                }
                ran.await(2, TimeUnit.SECONDS) shouldBe false
            }
        }

        test("without listening, a worker waits for its poll") {
            val source = jobsDatabase()
            val ran = CountDownLatch(1)
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done.also { ran.countDown() } })
            JobWorker(source, handlers, JobSettings(poll = 1.minutes, listen = false)).start().use { _ ->
                Thread.sleep(1_000)
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                ran.await(2, TimeUnit.SECONDS) shouldBe false
            }
        }
    }
}
