package dev.surefold.jobs.tenancy

import dev.surefold.jobs.JobCodec
import dev.surefold.jobs.JobHandlers
import dev.surefold.jobs.JobKind
import dev.surefold.jobs.JobResult
import dev.surefold.jobs.JobSettings
import dev.surefold.jobs.JobWorker
import dev.surefold.jobs.Jobs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.currentTenant
import dev.surefold.tenancy.withTenant
import dev.surefold.test.eventually
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate
import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import dev.surefold.jobs.JobRunWatcher as Watcher

private object Text : JobCodec<String> {
    override val version = 1

    override fun encode(payload: String) = "\"$payload\""

    override fun decode(version: Int, json: String) = json.trim('"')
}

private object Remind : JobKind<String>("remind", Text)

/** A job's handler runs as the tenant its row names (spec 0225). */
object TenantOfJobSuite : Suite() {
    init {
        test("a job enqueued for tenant B runs its handler as B, whoever enqueued it") {
            val source = database()
            val seen = ConcurrentHashMap<String, String>()
            val handlers = JobHandlers.of(
                Remind.handledBy { job ->
                    seen[job.payload] = currentTenant()?.value ?: "none"
                    JobResult.Done
                },
            )
            source.connection.use { connection ->
                Jobs.enqueue(connection, Remind, "for b", "for b", tenant = "b")
                withTenant(TenantId("a")) { Jobs.enqueue(connection, Remind, "a enqueues for c", "c", tenant = "c") }
                Jobs.enqueue(connection, Remind, "for nobody", "nobody")
            }
            JobWorker(source, handlers, JobSettings(poll = 20.milliseconds), watchers = listOf(TenantOfJob())).start()
                .use { _ -> eventually(within = 30.seconds) { seen.size shouldBe 3 } }
            seen shouldBe mapOf("for b" to "b", "a enqueues for c" to "c", "for nobody" to "none")
        }

        test("TenantOfJob is registered for the worker to find") {
            ServiceLoader.load(Watcher::class.java).map { it::class.simpleName } shouldBe listOf("TenantOfJob")
        }
    }

    private fun database(): DataSource = Postgres.empty().also { source ->
        source.migrate("surefold/jobs/postgres.sql")
    }
}
