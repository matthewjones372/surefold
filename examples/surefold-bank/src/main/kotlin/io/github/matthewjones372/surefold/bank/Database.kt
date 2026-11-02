package io.github.matthewjones372.surefold.bank

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.surefold.app.liquibase.migrate

/**
 * The Postgres database at [url], with the journal's tables, through a pool: Postgres starts a process for each
 * connection, too slow to pay on every append. The library leaves its tables to a service's migrations; this
 * application has no others, so it runs the changelog the jar ships, which Liquibase applies once.
 */
internal fun database(url: String): HikariDataSource = HikariDataSource(
    HikariConfig().apply {
        jdbcUrl = url
        maximumPoolSize = POOL
        poolName = "surefold-bank-journal"
    },
).also { pool -> migrate(pool, "surefold/journal/jdbc/postgres.sql") }

/**
 * Connections in a process's pool, however many of the three nodes it runs: small, since every process's pool counts
 * against the server's `max_connections`.
 */
private const val POOL = 10
