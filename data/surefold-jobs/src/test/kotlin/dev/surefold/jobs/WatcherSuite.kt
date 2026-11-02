package dev.surefold.jobs

import dev.surefold.Carriers
import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.logAnnotated
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.minutes

/** Registered through this module's test resources, to show a watcher is found there. */
class RegisteredWatcher : JobRunWatcher {
    override fun around(run: JobRunInfo, block: () -> String): String = block()
}

/** Every run, wrapped by each watcher (spec 0220). */
object WatcherSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("a watcher sees every run's kind, attempt and result, in order") {
            val source = jobsDatabase()
            val seen = ConcurrentLinkedQueue<String>()
            val watcher = object : JobRunWatcher {
                override fun around(run: JobRunInfo, block: () -> String): String =
                    block().also { result -> seen += "${run.kind} ${run.attempt} ${run.tenant} $result" }
            }
            val outcomes = ArrayDeque(listOf<() -> JobResult>({ error("down") }, { JobResult.Done }))
            val handlers = JobHandlers.of(Remind.handledBy { outcomes.removeFirst()() })
            val time = TestClock(noon)
            clock.locally(time) {
                source.committed { Jobs.enqueue(it, Remind, "call sam", "sam", tenant = "acme") }
                source.runDue(handlers, watcher)
                time.adjust(5.minutes)
                source.runDue(handlers, watcher)
            }
            seen.toList() shouldContainExactly listOf("remind 1 acme retry", "remind 2 acme done")
        }

        test("a watcher runs inside the context the enqueuer carried") {
            val source = jobsDatabase()
            val seen = ConcurrentLinkedQueue<String>()
            val watcher = object : JobRunWatcher {
                override fun around(run: JobRunInfo, block: () -> String): String {
                    seen += Carriers.capture().toString()
                    return block()
                }
            }
            clock.locally(TestClock(noon)) {
                logAnnotated("order" to "42") { source.committed { Jobs.enqueue(it, Remind, "call sam", "sam") } }
                source.runDue(JobHandlers.of(Remind.handledBy { JobResult.Done }), watcher)
            }
            seen.toList() shouldBe listOf("{surefold.annotation.order=42}")
        }

        test("a watcher registered through META-INF/services is found") {
            JobRunWatchers.registered.map { it::class.simpleName } shouldBe listOf("RegisteredWatcher")
        }
    }

    private fun DataSource.runDue(handlers: JobHandlers, watcher: JobRunWatcher) {
        val queue = Queue(this, "a")
        val run = JobRun(queue, handlers, watchers = listOf(watcher))
        queue.claim(limit = 10, lease = 1.minutes).forEach { run.run(it) }
    }
}
