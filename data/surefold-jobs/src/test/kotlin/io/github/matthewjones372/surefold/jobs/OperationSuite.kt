package io.github.matthewjones372.surefold.jobs

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes

private object Count : JobCodec<Int> {
    override val version = 1

    override fun encode(payload: Int) = "$payload"

    override fun decode(version: Int, json: String) = json.toInt()
}

/** Marks rows 1 to 10 two at a time, unless it is a dry run. */
private object MarkRows : JobKind<Int>("mark-rows", Count)

/** Long-running operations: a dry run, progress, and who started what (spec 0219). */
object OperationSuite : Suite() {
    private val noon = Instant.parse("2026-10-26T12:00:00Z")

    init {
        test("a dry run reports the same progress as the real one, and writes nothing") {
            val source = jobsDatabase()
            val marked = ConcurrentLinkedQueue<Int>()
            val handlers = JobHandlers.of(
                MarkRows.handledBy { job ->
                    val batch = (job.payload + 1..minOf(job.payload + 2, 10)).toList()
                    if (batch.isEmpty()) return@handledBy JobResult.Done
                    if (!job.dryRun) marked += batch
                    JobResult.Continue(batch.last(), progress = Progress(batch.size.toLong(), total = 10))
                },
            )
            val (dry, real) = clock.locally(TestClock(noon)) {
                val dry = source.committed { connection ->
                    Jobs.startOperation(connection, MarkRows, 0, startedBy = "ops:sam", dryRun = true)
                }
                source.drain(handlers)
                marked.toList() shouldBe emptyList()
                val real = source.committed { Jobs.startOperation(it, MarkRows, 0, startedBy = "ops:sam") }
                source.drain(handlers)
                dry to real
            }
            marked.toList() shouldBe (1..10).toList()
            val read = { id: UUID -> source.committed { Jobs.operation(it, id) }!! }
            read(dry).progress shouldBe Progress(10, 10)
            read(real).progress shouldBe Progress(10, 10)
            read(dry).dryRun shouldBe true
            read(real).status shouldBe "done"
        }

        test("an operation says who started it, with what, while it waits and after it is pruned") {
            val source = jobsDatabase()
            val time = TestClock(noon)
            clock.locally(time) {
                val id = source.committed { connection ->
                    Jobs.startOperation(connection, MarkRows, 4, startedBy = "ops:ana", tenant = "acme")
                }
                val waiting = source.committed { Jobs.operation(it, id) }!!
                waiting.startedBy shouldBe "ops:ana"
                waiting.parameters shouldBe "4"
                waiting.status shouldBe "pending"
                waiting.startedAt shouldBe noon
                val queue = Queue(source, "a")
                queue.claim(1, 1.minutes).forEach { queue.finish(it, Finished.Failed, "gave up") }
                source.committed { Jobs.operation(it, id) }!!.lastError shouldBe "gave up"
                time.adjust(30.days)
                queue.prune(retention = 7.days)
                source.committed { Jobs.operation(it, id) }!!.status shouldBe "pruned"
                source.committed { Jobs.operation(it, UUID.randomUUID()) } shouldBe null
            }
        }
    }

    private fun DataSource.drain(handlers: JobHandlers) {
        val queue = Queue(this, "a")
        while (true) {
            val claimed = queue.claim(limit = 1, lease = 1.minutes)
            if (claimed.isEmpty()) return
            claimed.forEach { JobRun(queue, handlers).run(it) }
        }
    }
}
