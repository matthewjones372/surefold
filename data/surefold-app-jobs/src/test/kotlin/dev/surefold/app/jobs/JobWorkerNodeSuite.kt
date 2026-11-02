package dev.surefold.app.jobs

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.app.Module
import dev.surefold.app.liquibase.migrations
import dev.surefold.app.singleOf
import dev.surefold.app.testApp
import dev.surefold.jobs.JobCodec
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobKind
import dev.surefold.jobs.JobResult
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
import dev.surefold.jobs.Jobs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import java.util.concurrent.ConcurrentLinkedQueue
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private object Text : JobCodec<String> {
    override val version = 1

    override fun encode(payload: String) = "\"$payload\""

    override fun decode(version: Int, json: String) = json.trim('"')
}

private object Greet : JobKind<String>("greet", Text)

/** The worker as a node: after the migrations, and drained on release (spec 0216). */
object JobWorkerNodeSuite : Suite() {
    init {
        test("a worker node runs the jobs tables' changelog first, runs what is enqueued, and stops on release") {
            val greeted = ConcurrentLinkedQueue<String>()
            val source = Postgres.empty()
            val module: Module =
                singleOf<DataSource> { source } +
                    migrations("surefold/jobs/postgres.sql") +
                    singleOf { -> JobSettings(poll = 20.milliseconds) } +
                    jobWorker(JobHandlers.of(Greet.handledBy { job -> JobResult.Done.also { greeted += job.payload } }))

            testApp(module) { worker: JobWorker ->
                source.connection.use { connection -> Jobs.enqueue(connection, Greet, "sam", "sam") }
                eventually(within = 30.seconds) { greeted.toList() shouldBe listOf("sam") }
                worker.name.isNotBlank() shouldBe true
            }
        }
    }
}
