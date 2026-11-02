package dev.surefold.actor.journal.jdbc

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.postgresql.ds.PGSimpleDataSource
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * One real Postgres for the test JVM, in a container started on first use and stopped when the JVM exits, and a fresh
 * database in it for each test. A test fixture, so the modules that test on a journal share the one server.
 */
object Postgres {
    private val server: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("public.ecr.aws/docker/library/postgres:17").asCompatibleSubstituteFor("postgres"),
        ).apply {
            // A test's pool and every node's connections share one server.
            // A server thrown away after the run: no password, so no connection pays for SCRAM's key stretching.
            withEnv("POSTGRES_HOST_AUTH_METHOD", "trust")
            withCommand("postgres", "-c", "max_connections=500")
            start()
        }
    }
    private val made = AtomicInteger()

    /** A fresh database with the journal's changelog applied. */
    fun fresh(): DataSource = empty().migrated()

    /**
     * A fresh database with nothing in it, through a pool as a service reaches one: Postgres starts a process for
     * each connection, too slow to pay on every append. Idle connections close, so pools a test leaves cost nothing.
     */
    fun empty(): DataSource = HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = emptyUrl()
            maximumPoolSize = 16
            minimumIdle = 0
            idleTimeout = 10_000
        },
    )

    /** A fresh database with nothing in it, as a JDBC URL that carries its user and password. */
    fun emptyUrl(): String {
        val name = "surefold_${made.incrementAndGet()}"
        PGSimpleDataSource().apply { setUrl(url(server.databaseName)) }.connection.use { admin ->
            admin.createStatement().use { statement -> statement.execute("create database $name") }
        }
        return url(name)
    }

    private fun url(database: String) = "jdbc:postgresql://${server.host}:${server.getMappedPort(5432)}/$database" +
        "?user=${server.username}&password=${server.password}"
}
