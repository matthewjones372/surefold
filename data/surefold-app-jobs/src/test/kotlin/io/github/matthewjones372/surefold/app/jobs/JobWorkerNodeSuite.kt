package io.github.matthewjones372.surefold.app.jobs

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.liquibase.migrations
import io.github.matthewjones372.surefold.app.singleOf
import io.github.matthewjones372.surefold.app.testApp
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.JobWorker
import io.github.matthewjones372.surefold.jobs.Jobs
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
