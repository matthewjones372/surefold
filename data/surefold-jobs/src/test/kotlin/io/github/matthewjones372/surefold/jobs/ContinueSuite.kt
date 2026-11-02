package io.github.matthewjones372.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** A cursor kept in the payload, so a job of many runs resumes where it got to (spec 0219). */
private object Cursor : JobCodec<Int> {
    override val version = 1

    override fun encode(payload: Int) = "$payload"

    override fun decode(version: Int, json: String) = json.toInt()
}

private object Walk : JobKind<Int>("walk", Cursor)

object ContinueSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("a walk over 10,000 rows, 500 a run, killed after run 7, resumes from row 3,501") {
            val source = jobsDatabase()
            val starts = ConcurrentLinkedQueue<Int>()
            val handlers = JobHandlers.of(
                Walk.handledBy { job ->
                    val from = job.payload + 1
                    starts += from
                    val to = minOf(from + 499, ROWS)
                    if (from > ROWS) {
                        JobResult.Done
                    } else {
                        JobResult.Continue(to, progress = Progress(done = (to - from + 1).toLong(), total = 10_000))
                    }
                },
            )
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Walk, 0, "walk") }
                repeat(7) { source.runOnce(handlers, "a") }
                // Run 8 is claimed by a worker that dies before it answers; its lease expires and another takes it.
                Queue(source, "dies").claim(limit = 1, lease = 1.minutes).size shouldBe 1
                time.adjust(2.minutes)
                Queue(source, "b").sweep() shouldBe 1
                while (source.column("select count(*) from surefold_jobs").single() == 1L) {
                    source.runOnce(handlers, "b")
                }
            }
            starts.toList()[7] shouldBe 3_501
            starts.last() shouldBe 10_001
            source.column("select status || ' ' || progress_done || '/' || progress_total from surefold_jobs_history")
                .shouldBe(listOf("done 10000/10000"))
        }

        test("a Continue gives its attempt back, and the handler sees the progress so far") {
            val source = jobsDatabase()
            val seen = ConcurrentLinkedQueue<String>()
            val handlers = JobHandlers.of(
                Walk.handledBy { job ->
                    seen += "${job.attempt} ${job.progress.done}"
                    if (job.payload >=
                        3
                    ) JobResult.Done else JobResult.Continue(job.payload + 1, progress = Progress(1))
                },
            )
            clock.locally(TestClock(noon)) {
                source.committed { Jobs.enqueue(it, Walk, 0, "walk") }
                repeat(4) { source.runOnce(handlers, "a") }
            }
            seen.toList() shouldBe listOf("1 0", "1 1", "1 2", "1 3")
        }
    }

    private const val ROWS = 10_000

    private fun DataSource.runOnce(handlers: JobHandlers, worker: String) {
        val queue = Queue(this, worker)
        queue.claim(limit = 1, lease = 1.minutes).forEach { JobRun(queue, handlers).run(it) }
    }
}
