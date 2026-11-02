package io.github.matthewjones372.surefold.app.flyway

import dev.surefold.logInfo
import io.github.matthewjones372.surefold.app.Migrated
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.Wiring
import io.github.matthewjones372.surefold.app.single
import org.flywaydb.core.Flyway
import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension
import javax.sql.DataSource

/**
 * What a service may change about a migration; the rest is pinned. [mixed] is off because Postgres DDL is
 * transactional: a script that needs `create index concurrently` says so in its own `.sql.conf`. [transactionalLock]
 * is off so that such a script does not wait on the lock's own transaction, and [connectRetries] is 0 so that a
 * service that cannot reach Postgres fails to start and is restarted.
 */
data class FlywaySettings(
    val mixed: Boolean = false,
    val transactionalLock: Boolean = false,
    val connectRetries: Int = 0,
)

/**
 * Runs [modules]' scripts, in [SurefoldSchema] order whatever the argument order, then those at [service], a Flyway
 * location such as `classpath:db/migration`, against the graph's [DataSource], once, before anything that takes a
 * [Migrated] is built. Flyway takes its connections from the source and gives them back before the node answers. A
 * script that fails, or an applied one since edited, refuses the start. A database Liquibase has migrated until now is
 * refused unless [fromLiquibase] names the last changeset it applied, `author:id`; then it is handed over once.
 */
fun flyway(
    vararg modules: SurefoldSchema,
    service: String? = null,
    settings: FlywaySettings = FlywaySettings(),
    fromLiquibase: String? = null,
): Module = single { source: DataSource ->
    migrated { migrate(source, *modules, service = service, settings = settings, fromLiquibase = fromLiquibase) }
}

/**
 * [flyway] on a source [connect] opens from [S], a node in the graph, such as a pool logged in as the schema's owner.
 * It is closed, if it is `AutoCloseable`, before the node answers: the application never holds it.
 */
inline fun <reified S : Any> flyway(
    vararg modules: SurefoldSchema,
    service: String? = null,
    settings: FlywaySettings = FlywaySettings(),
    fromLiquibase: String? = null,
    noinline connect: (S) -> DataSource,
): Module = single { key: S ->
    migrated {
        connected(connect(key)) { source ->
            migrate(source, *modules, service = service, settings = settings, fromLiquibase = fromLiquibase)
        }
    }
}

/**
 * Runs [modules]' scripts and then [service]'s against [source] now, as [flyway] does, and answers how many it applied.
 * For a database outside a graph.
 */
fun migrate(
    source: DataSource,
    vararg modules: SurefoldSchema,
    service: String? = null,
    settings: FlywaySettings = FlywaySettings(),
    fromLiquibase: String? = null,
): Int {
    if (source.hasTable(LIQUIBASE)) {
        fromLiquibase ?: throw FlywayException(
            "this database has Liquibase's $LIQUIBASE: hand it over to Flyway with fromLiquibase, as " +
                "docs/flyway.md describes, rather than running Flyway's scripts over Liquibase's tables",
        )
        handOver(source, modules.toSortedSet(), service, settings, fromLiquibase)
    }
    val runs =
        modules.toSortedSet().map { it.location to it.table } + listOfNotNull(service?.let { it to SERVICE_TABLE })
    return runs.sumOf { (location, table) ->
        val flyway = configured(source, location, settings, table)
        if (flyway.info().applied().isEmpty()) started(flyway, source, location, settings, table)
        val applied = flyway.migrate().migrationsExecuted
        logInfo("flyway: $location, $applied migration(s) applied")
        applied
    }
}

/**
 * Starts [table] at a baseline of version 0. Flyway refuses a non-empty schema that has no history table, and every
 * table after the first meets one; at 0 no script is skipped, so a first script that meets existing tables fails with
 * `relation already exists` instead.
 */
private fun started(flyway: Flyway, source: DataSource, location: String, settings: FlywaySettings, table: String) {
    flyway.info().all().firstOrNull { it.version != null && it.version <= ZERO }?.let { skipped ->
        throw FlywayException("${skipped.script} is at or below the baseline of 0, so Flyway would skip it")
    }
    configured(source, location, settings, table, ZERO).baseline()
}

private val ZERO = MigrationVersion.fromVersion("0")

/** Flyway's own default, so a service's history is where Flyway's tools look for it. */
internal const val SERVICE_TABLE = "flyway_schema_history"

/** Flyway as every migration here runs it: what [FlywaySettings] does not offer is pinned. */
internal fun configured(
    source: DataSource,
    location: String,
    settings: FlywaySettings,
    table: String = SERVICE_TABLE,
    baseline: MigrationVersion? = null,
): Flyway =
    Flyway.configure()
        // Set on the extension itself: as a property, Flyway wants jackson-databind on the classpath to read it.
        .apply {
            getConfigurationExtension(PostgreSQLConfigurationExtension::class.java).isTransactionalLock =
                settings.transactionalLock
        }
        .dataSource(source)
        .locations(location)
        .table(table)
        .apply { baseline?.let { baselineVersion(it) } }
        // A clean drops the schema, which no service wants in production.
        .cleanDisabled(true)
        // An applied script edited since fails the start with its checksum.
        .validateOnMigrate(true)
        // It would baseline any non-empty schema, silently.
        .baselineOnMigrate(false)
        .outOfOrder(false)
        // A `${` in a function body is SQL.
        .placeholderReplacement(false)
        .mixed(settings.mixed)
        .connectRetries(settings.connectRetries)
        .load()

/** [migrate]'s count as a [Migrated], or the start refused with what Flyway said. */
@PublishedApi
internal fun Wiring.migrated(migrate: () -> Int): Migrated =
    try {
        Migrated(migrate())
    } catch (failed: FlywayException) {
        refuse("the migration failed: ${failed.message}")
    }

/** [use] on [source], closing it afterwards if it can be closed. */
@PublishedApi
internal fun <T> connected(source: DataSource, use: (DataSource) -> T): T =
    try {
        use(source)
    } finally {
        (source as? AutoCloseable)?.close()
    }
