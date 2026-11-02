package dev.surefold.app.flyway

import org.flywaydb.core.api.FlywayException
import org.flywaydb.core.api.MigrationVersion
import javax.sql.DataSource

/**
 * Baselines a database Liquibase has migrated until now, once: the service at `V1`, and each module at the version of
 * its last changeset Liquibase applied. Only while the service has no Flyway history, and only when Liquibase's last
 * changeset is [fromLiquibase]; any other database is refused rather than guessed at.
 */
internal fun handOver(
    source: DataSource,
    modules: Collection<SurefoldSchema>,
    service: String?,
    settings: FlywaySettings,
    fromLiquibase: String,
) {
    if (source.rows(SERVICE_TABLE) > 0) return
    val last = if (source.hasTable(LIQUIBASE)) {
        source.strings("select author || ':' || id from $LIQUIBASE order by orderexecuted desc limit 1").firstOrNull()
    } else {
        null
    }
    if (last != fromLiquibase) {
        throw FlywayException(
            "fromLiquibase is $fromLiquibase, but the last changeset Liquibase applied here is " +
                (last ?: "none: there is no $LIQUIBASE"),
        )
    }
    modules.filter { source.rows(it.table) == 0 }.forEach { module ->
        appliedVersion(source, module, settings)?.let { version ->
            configured(source, module.location, settings, module.table, version).baseline()
        }
    }
    service?.let { configured(source, it, settings, SERVICE_TABLE, MigrationVersion.fromVersion("1")).baseline() }
}

/** Liquibase's history table. */
internal const val LIQUIBASE = "databasechangelog"

/** The version of [module]'s script whose changeset Liquibase applied last, or null if it applied none of them. */
private fun appliedVersion(source: DataSource, module: SurefoldSchema, settings: FlywaySettings): MigrationVersion? {
    // A script's description is its changeset's id: V2__jobs-leases.sql is surefold:jobs-leases.
    val versions = configured(source, module.location, settings, module.table).info().all()
        .filter { it.version != null }
        .associate { it.description to it.version }
    val applied = source.strings("select id from $LIQUIBASE where author = 'surefold'").toSet()
    return versions.filterKeys { it in applied }.values.maxOrNull()
}

/** How many rows [table] holds, or 0 if there is no such table. */
private fun DataSource.rows(table: String): Int =
    if (hasTable(table)) strings("select count(*) from $table").single().toInt() else 0
