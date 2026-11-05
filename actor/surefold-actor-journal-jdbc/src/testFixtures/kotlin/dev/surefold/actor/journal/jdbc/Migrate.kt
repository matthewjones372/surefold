package dev.surefold.actor.journal.jdbc

import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import liquibase.Liquibase
import liquibase.database.DatabaseFactory
import liquibase.database.jvm.JdbcConnection
import liquibase.resource.ClassLoaderResourceAccessor

// Liquibase keeps its lock services in one registry for the JVM, so two updates at once, even on different
// databases, can leave one database's changelog lock taken for good and the next update waiting on it. Tests run at
// once: their updates run one at a time.
private val updating = ReentrantLock()

/**
 * [changelog], a classpath resource, applied by Liquibase as a service would; answers how many changesets ran. One
 * update at a time in the JVM, so suites that run at once can each migrate their own database.
 */
fun DataSource.migrate(changelog: String): Int = updating.withLock {
    // Liquibase closes the connection it is handed.
    connection.use { connection ->
        val database = DatabaseFactory.getInstance().findCorrectDatabaseImplementation(JdbcConnection(connection))
        Liquibase(changelog, ClassLoaderResourceAccessor(), database).use { liquibase ->
            liquibase.listUnrunChangeSets(null, null).size.also { liquibase.update() }
        }
    }
}

/** The changelog the jar ships, applied by Liquibase as a service would; answers how many changesets ran. */
fun DataSource.migrate(): Int = migrate("surefold/journal/jdbc/postgres.sql")

/** This database with the changelog applied. */
fun DataSource.migrated(): DataSource = also { migrate() }
