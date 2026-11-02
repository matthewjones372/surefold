package dev.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.capturingMetrics
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldHaveSize
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

/** What a worker measures: runs by result, how long each ran, and the backlog per kind (spec 0216). */
object MetricsSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("each run is counted by its kind and result, and timed") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done })
            capturingMetrics { metrics ->
                clock.locally(TestClock(noon)) {
                    source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                    val queue = Queue(source, "a")
                    queue.claim(limit = 1, lease = 1.minutes).forEach { JobRun(queue, handlers).run(it) }
                }
                metrics.counter(RUNS) shouldBe 1.0
                metrics.tags(RUNS) shouldBe mapOf("kind" to "remind", "result" to "done")
                metrics.histogram(DURATION) shouldHaveSize 1
            }
        }

        test("a failed run is counted as failed") {
            val source = jobsDatabase()
            capturingMetrics { metrics ->
                clock.locally(TestClock(noon)) {
                    source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") }
                    val queue = Queue(source, "a")
                    queue.claim(limit = 1, lease = 1.minutes).forEach { JobRun(queue, JobHandlers.of()).run(it) }
                }
                metrics.tags(RUNS) shouldBe mapOf("kind" to "remind", "result" to "failed")
            }
        }

        test("the backlog is each kind's jobs due and waiting, and how long the oldest has waited") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done })
            capturingMetrics { metrics ->
                clock.locally(TestClock(noon)) {
                    source.committed { connection ->
                        (1..3).forEach { n ->
                            Jobs.enqueue(connection, Remind, "$n", "$n", runAt = noon.minusSeconds(30L * n))
                        }
                        Jobs.enqueue(connection, Remind, "later", "later", runAt = noon.plusSeconds(60))
                    }
                    JobWorker(source, handlers).measureBacklog()
                }
                metrics.gauge("surefold.jobs.depth") shouldBe 3.0
                metrics.gauge("surefold.jobs.lag.seconds") shouldBe 90.0
                metrics.tags("surefold.jobs.depth") shouldBe mapOf("kind" to "remind")
            }
        }

        test("a kind with nothing due reads as an empty backlog") {
            val source = jobsDatabase()
            capturingMetrics { metrics ->
                JobWorker(source, JobHandlers.of(Remind.handledBy { JobResult.Done })).measureBacklog()
                metrics.gauge("surefold.jobs.depth") shouldBe 0.0
                metrics.gauge("surefold.jobs.lag.seconds") shouldBe 0.0
            }
        }
    }
}
