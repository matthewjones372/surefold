package io.github.matthewjones372.surefold.jobs.load

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.surefold.clock
import io.github.matthewjones372.proofload.at
import io.github.matthewjones372.proofload.engine.run
import io.github.matthewjones372.proofload.lostGround
import io.github.matthewjones372.proofload.perSecond
import io.github.matthewjones372.proofload.scenario
import io.github.matthewjones372.proofload.step
import io.github.matthewjones372.surefold.jobs.JobCodec
import io.github.matthewjones372.surefold.jobs.JobHandlers
import io.github.matthewjones372.surefold.jobs.JobKind
import io.github.matthewjones372.surefold.jobs.JobResult
import io.github.matthewjones372.surefold.jobs.JobSettings
import io.github.matthewjones372.surefold.jobs.JobWorker
import io.github.matthewjones372.surefold.jobs.Jobs
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.Locale
import java.util.UUID
import javax.sql.DataSource
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private object Empty : JobCodec<Unit> {
    override val version = 1

    override fun encode(payload: Unit) = "{}"

    override fun decode(version: Int, json: String) = Unit
}

/** A job that does nothing, so what is measured is the table: enqueue, claim, finish. */
private object Noop : JobKind<Unit>("noop", Empty, concurrency = 16)

private val enqueueToDone = step("enqueue to done")

/** Runs the load at each worker count, and prints a line for each. */
fun main(args: Array<String>) {
    val rate = args.getOrNull(0)?.toInt() ?: 200
    val seconds = args.getOrNull(1)?.toInt() ?: 30
    PostgreSQLContainer(
        DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
    ).withCommand("postgres", "-c", "max_connections=500").use { postgres ->
        postgres.start()
        val source = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 200
            },
        )
        source.migrate()
        println("workers  rate/s  done/s   p50 ms    p99 ms  kept schedule")
        for (workers in WORKER_COUNTS) println(source.measure(workers, rate, seconds))
        source.close()
    }
}

private fun DataSource.measure(workers: Int, rate: Int, seconds: Int): String {
    connection.use { it.createStatement().use { s -> s.execute("truncate surefold_jobs, surefold_jobs_history") } }
    val handlers = JobHandlers.of(Noop.handledBy { JobResult.Done })
    val running = (1..workers).map { JobWorker(this, handlers, JobSettings(poll = 50.milliseconds)).start() }
    val load = scenario("jobs") {
        exec(enqueueToDone) {
            val key = UUID.randomUUID().toString()
            connection.use { connection -> Jobs.enqueue(connection, Noop, Unit, key) }
            val started = TimeSource.Monotonic.markNow()
            while (!done(key)) {
                if (started.elapsedNow() > 30.seconds) fail("not done within 30 s")
                clock.get().sleep(2.milliseconds)
            }
        }
    }
    val started = TimeSource.Monotonic.markNow()
    val result = load.at(rate.perSecond, over = seconds.seconds).run()
    val elapsed = started.elapsedNow()
    running.forEach { it.close() }
    val stats = result[enqueueToDone]
    val done = connection.use { connection ->
        connection.createStatement().use { s ->
            s.executeQuery("select count(*) from surefold_jobs_history").use { rows -> rows.next(); rows.getLong(1) }
        }
    }
    return "%7d  %6d  %6.0f  %7.1f  %8.1f  %s".format(
        Locale.ROOT,
        workers,
        rate,
        done / elapsed.inWholeMilliseconds.toDouble() * MILLIS_PER_SECOND,
        stats.responseTime.p50.inWholeMicroseconds / MICROS_PER_MILLI,
        stats.responseTime.p99.inWholeMicroseconds / MICROS_PER_MILLI,
        if (result.lostGround()) "no" else "yes",
    )
}

private fun DataSource.done(key: String): Boolean = connection.use { connection ->
    connection.prepareStatement("select 1 from surefold_jobs_history where dedupe_key = ?").use { statement ->
        statement.setString(1, key)
        statement.executeQuery().use { it.next() }
    }
}

private fun DataSource.migrate() = connection.use { connection ->
    val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
    Liquibase("surefold/jobs/postgres.sql", ClassLoaderResourceAccessor(), database).use { it.update() }
}

private val WORKER_COUNTS = listOf(1, 4, 16)
private const val MILLIS_PER_SECOND = 1_000.0
private const val MICROS_PER_MILLI = 1_000.0
