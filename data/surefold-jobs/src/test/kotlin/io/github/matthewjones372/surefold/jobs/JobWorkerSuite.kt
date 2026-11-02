package io.github.matthewjones372.surefold.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.test.eventually
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** The polling worker: concurrency per kind, and a graceful close (spec 0216). */
object JobWorkerSuite : Suite() {
    private val quick = JobSettings(poll = 20.milliseconds, drain = 10.seconds)

    init {
        test("a worker runs every due job, never more of a kind at once than its concurrency") {
            val source = jobsDatabase()
            val three = object : JobKind<String>("three", Notes, concurrency = 3) {}
            val now = AtomicInteger()
            val most = AtomicInteger()
            val handlers = JobHandlers.of(
                three.handledBy {
                    most.accumulateAndGet(now.incrementAndGet(), ::maxOf)
                    Thread.sleep(30)
                    now.decrementAndGet()
                    JobResult.Done
                },
            )
            source.committed { connection -> (1..20).forEach { Jobs.enqueue(connection, three, "$it", "$it") } }
            JobWorker(source, handlers, quick).start().use {
                eventually(within = 30.seconds) {
                    source.column("select count(*) from surefold_jobs_history where status = 'done'") shouldBe
                        listOf(20L)
                }
            }
            most.get() shouldBe 3
        }

        test("a worker fails a job of a kind it has no handler for") {
            val source = jobsDatabase()
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            JobWorker(source, JobHandlers.of(), quick).start().use {
                eventually(within = 30.seconds) {
                    source.column("select status from surefold_jobs_history") shouldBe listOf("failed")
                }
            }
        }

        test("closing lets a run in flight finish within the drain, and claims nothing after") {
            val source = jobsDatabase()
            val started = CountDownLatch(1)
            val release = CountDownLatch(1)
            val handlers = JobHandlers.of(
                Remind.handledBy {
                    started.countDown()
                    release.await()
                    JobResult.Done
                },
            )
            source.committed { connection ->
                Jobs.enqueue(connection, Remind, "first", "first")
                Jobs.enqueue(connection, Remind, "second", "second", runAt = java.time.Instant.now().plusSeconds(1))
            }
            val worker = JobWorker(source, handlers, quick).start()
            started.await(30, TimeUnit.SECONDS) shouldBe true
            val closing = Thread.ofVirtual().start { worker.close() }
            Thread.sleep(1_500)
            release.countDown()
            closing.join()
            source.column("select dedupe_key || ' ' || status from surefold_jobs_history") shouldBe listOf("first done")
            source.column("select dedupe_key || ' ' || status || ' ' || attempts from surefold_jobs") shouldBe
                listOf("second pending 0")
        }

        test("closing interrupts a run still going when the drain ends, and leaves its lease to expire") {
            val source = jobsDatabase()
            val started = CountDownLatch(1)
            val interrupted = AtomicBoolean(false)
            val handlers = JobHandlers.of(
                Remind.handledBy {
                    started.countDown()
                    try {
                        Thread.sleep(60_000)
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                    }
                    JobResult.Done
                },
            )
            source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
            val worker = JobWorker(source, handlers, quick.copy(drain = 200.milliseconds)).start()
            started.await(30, TimeUnit.SECONDS) shouldBe true
            val closing = TimeSource.Monotonic.markNow()
            worker.close()
            closing.elapsedNow() shouldBeLessThan 5.seconds
            eventually(within = 5.seconds) { interrupted.get() shouldBe true }
        }
    }
}
