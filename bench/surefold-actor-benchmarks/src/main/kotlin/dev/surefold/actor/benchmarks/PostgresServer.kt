package dev.surefold.actor.benchmarks

import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import javax.sql.DataSource

/**
 * A Postgres in a container for one trial, stopped by [close]. Commits are durable, as a service runs them: without
 * fsync and synchronous commit a server is CPU-bound, not commit-bound, and a second on the same cores has nothing
 * of its own to add.
 */
internal class PostgresServer : AutoCloseable {
    private val container = PostgreSQLContainer(
        DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
    ).apply {
        // A server thrown away after the run: no password, so no connection pays for SCRAM's key stretching.
        withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
        withCommand("postgres", "-c", "fsync=on", "-c", "synchronous_commit=on", "-c", "max_connections=200")
        start()
    }

    val url: String get() = container.jdbcUrl
    val user: String get() = container.username
    val password: String get() = container.password

    /** A connection each time, unpooled: for migrations, not for the measured path. */
    val dataSource: DataSource
        get() = PGSimpleDataSource().also { source ->
            source.setUrl(url)
            source.user = user
            source.password = password
        }

    override fun close() = container.stop()
}
