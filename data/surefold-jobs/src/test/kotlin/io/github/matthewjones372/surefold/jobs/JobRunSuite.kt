package io.github.matthewjones372.surefold.jobs

import dev.surefold.Schedule
import dev.surefold.TestClock
import dev.surefold.capturingLogs
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Handlers, their results, deadlines, backoff, expiry and decode failures (spec 0216). */
object JobRunSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    // Doubling from a second with no jitter, so a test can name each delay.
    private val doubling: Schedule<Unit, Any?> = Schedule.exponential(1.seconds)

    init {
        test("a handler's Done, Skip and Fail move each job to history as it said") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(
                Remind.handledBy { job ->
                    when (job.payload) {
                        "done" -> JobResult.Done
                        "skip" -> JobResult.Skip("not wanted")
                        else -> JobResult.Fail("cannot")
                    }
                },
            )
            clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    listOf("done", "skip", "fail").forEach { Jobs.enqueue(connection, Remind, it, it) }
                }
                source.runAll(handlers)
            }
            source.column(
                "select dedupe_key || ' ' || status || ' ' || coalesce(last_error, '-') " +
                    "from surefold_jobs_history order by 1",
            )
                .shouldContainExactly(listOf("done done -", "fail failed cannot", "skip skipped not wanted"))
        }

        test("a Retry the handler asks for runs again after its delay, using an attempt, and fails on its last") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Retry(5.minutes, "busy") })
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", maxAttempts = 2) }
                source.runAll(handlers)
                source.column("select status || ' ' || attempts || ' ' || last_error from surefold_jobs") shouldBe
                    listOf("pending 1 busy")
                source.column("select run_at from surefold_jobs") shouldBe
                    listOf(Timestamp.from(noon.plusSeconds(300)))
                time.setTime(noon.plusSeconds(300))
                source.runAll(handlers)
            }
            source.column("select status || ' ' || attempts || ': ' || last_error from surefold_jobs_history") shouldBe
                listOf("failed 2: busy, on attempt 2 of 2")
        }

        test("a throwing handler is retried with growing delays, and fails on its last attempt") {
            val source = jobsDatabase()
            val handlers = JobHandlers.of(Remind.handledBy { throw IllegalStateException("smtp down") })
            val time = TestClock(noon)
            val delays = clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", maxAttempts = 4) }
                (1..3).map {
                    source.runAll(handlers, doubling) shouldBe 1
                    val next = source.runAt()
                    Duration.between(time.now(), next).also { time.setTime(next) }
                }.also { source.runAll(handlers, doubling) shouldBe 1 }
            }
            delays shouldContainExactly listOf(Duration.ofSeconds(1), Duration.ofSeconds(2), Duration.ofSeconds(4))
            source.column("select status || ' ' || attempts || ': ' || last_error from surefold_jobs_history") shouldBe
                listOf("failed 4: it threw java.lang.IllegalStateException: smtp down, on attempt 4 of 4")
        }

        test("a run past its deadline is interrupted and retried") {
            val source = jobsDatabase()
            val slow = object : JobKind<String>("slow", Notes, deadline = 200.milliseconds) {}
            val interrupted = AtomicBoolean(false)
            val handlers = JobHandlers.of(
                slow.handledBy {
                    try {
                        Thread.sleep(30_000)
                    } catch (_: InterruptedException) {
                        interrupted.set(true)
                    }
                    JobResult.Done
                },
            )
            source.committed { Jobs.enqueue(it, slow, "wait", "wait") }
            source.runAll(handlers, doubling)
            source.column("select status || ' ' || attempts || ': ' || last_error from surefold_jobs") shouldBe
                listOf("pending 1: no answer within 200ms")
            interrupted.get() shouldBe true
        }

        test("a job past its expiry is skipped without running its handler") {
            val source = jobsDatabase()
            var ran = false
            val handlers = JobHandlers.of(Remind.handledBy { JobResult.Done.also { ran = true } })
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", expiresAt = noon.plusSeconds(60)) }
                time.setTime(noon.plusSeconds(61))
                source.runAll(handlers)
            }
            ran shouldBe false
            source.column("select status || ': ' || last_error from surefold_jobs_history") shouldBe
                listOf("skipped: expired at 2026-10-26T12:01:00Z")
        }

        test("a kind nothing handles, and a payload its codec cannot read, fail at once with attempts left") {
            val source = jobsDatabase()
            val unread = object : JobKind<String>(
                "unread",
                object : JobCodec<String> by Notes {
                    override fun decode(version: Int, json: String): String = error("version $version is too new")
                },
            ) {}
            val handlers = JobHandlers.of(unread.handledBy { JobResult.Done })
            clock.locally(TestClock(noon)) {
                source.committed { connection ->
                    Jobs.enqueue(connection, Remind, "call sam", "sam")
                    Jobs.enqueue(connection, unread, "call ana", "ana")
                }
                source.runAll(handlers)
            }
            source.column("select kind || ' ' || status || ': ' || last_error from surefold_jobs_history order by 1")
                .shouldContainExactly(
                    listOf(
                        "remind failed: no handler for kind \"remind\"",
                        "unread failed: its payload could not be read: " +
                            "java.lang.IllegalStateException: version 2 is too new",
                    ),
                )
        }

        test("a handler sees its decoded payload and attempt, under what the enqueuing thread carried") {
            val source = jobsDatabase()
            val seen = mutableListOf<String>()
            val handlers = JobHandlers.of(
                Remind.handledBy { job ->
                    seen += "${job.payload} ${job.attempt} ${job.tenant}"
                    logInfo("running")
                    JobResult.Done
                },
            )
            val lines = capturingLogs { logs ->
                clock.locally(TestClock(noon)) {
                    logAnnotated("order" to "42") {
                        source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
                    }
                    source.runAll(handlers)
                }
                logs.all()
            }
            seen shouldContainExactly listOf("call sam 1 acme")
            lines.single { it.message == "running" }.annotations shouldContain ("order" to "42")
        }

        test("two handlers for one kind are refused") {
            shouldThrow<IllegalArgumentException> {
                JobHandlers.of(Remind.handledBy { JobResult.Done }, Remind.handledBy { JobResult.Done })
            }
        }
    }

    /** Claims every due job and runs it, and answers how many. */
    private fun DataSource.runAll(handlers: JobHandlers, backoff: Schedule<Unit, Any?> = doubling): Int {
        val queue = Queue(this, "a")
        val claimed = queue.claim(limit = 100, lease = 1.minutes)
        claimed.forEach { JobRun(queue, handlers, backoff).run(it) }
        return claimed.size
    }

    private fun DataSource.runAt(): Instant = (column("select run_at from surefold_jobs").single() as Timestamp)
        .toInstant()
}
