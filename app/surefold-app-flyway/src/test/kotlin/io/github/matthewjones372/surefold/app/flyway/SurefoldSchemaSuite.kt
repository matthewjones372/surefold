package io.github.matthewjones372.surefold.app.flyway

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import javax.sql.DataSource

/**
 * What a schema holds, as sorted lines: tables, columns, constraints, indexes, policies, grants and functions, without
 * either tool's own history tables.
 */
private val CATALOG = listOf(
    """
    select 'table ' || c.relname || ' rls=' || c.relrowsecurity || ' force=' || c.relforcerowsecurity
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where n.nspname = 'public' and c.relkind in ('r', 'p', 'v', 'm', 'S')
    """,
    """
    select 'column ' || table_name || '.' || column_name || ' #' || ordinal_position || ' ' || data_type || ' ' ||
        is_nullable || ' ' || coalesce(column_default, '') || ' ' || is_identity
    from information_schema.columns where table_schema = 'public'
    """,
    """
    select 'constraint ' || conrelid::regclass || ' ' || conname || ' ' || pg_get_constraintdef(oid)
    from pg_constraint where connamespace = 'public'::regnamespace
    """,
    "select 'index ' || indexname || ' ' || indexdef from pg_indexes where schemaname = 'public'",
    """
    select 'policy ' || tablename || ' ' || policyname || ' ' || permissive || ' ' || array_to_string(roles, ',') ||
        ' ' || cmd || ' ' || coalesce(qual, '') || ' ' || coalesce(with_check, '')
    from pg_policies where schemaname = 'public'
    """,
    """
    select 'grant ' || table_name || ' ' || grantee || ' ' || privilege_type
    from information_schema.role_table_grants where table_schema = 'public'
    """,
    """
    select 'function ' || p.proname || ' ' || pg_get_functiondef(p.oid)
    from pg_proc p where p.pronamespace = 'public'::regnamespace
    """,
)

private val HISTORY = Regex("""\b(databasechangelog\w*|flyway_\w+)\b""")

internal fun catalog(source: DataSource): List<String> =
    source.connection.use { connection ->
        CATALOG.flatMap { sql ->
            connection.createStatement().use { statement ->
                statement.executeQuery(sql.trimIndent()).use { rows ->
                    generateSequence { if (rows.next()) rows.getString(1) else null }.toList()
                }
            }
        }
    }.filterNot { HISTORY.containsMatchIn(it) }.sorted()

/** The data modules' two copies of their SQL (spec 0242): Flyway's scripts and Liquibase's changelogs agree. */
object SurefoldSchemaSuite : Suite() {
    init {
        testEach(
            "a module's Flyway scripts leave the catalog its Liquibase changelog does",
            SurefoldSchema.entries,
            named = { it.name.lowercase() },
        ) { schema ->
            val liquibase = Postgres.empty().also { it.migrate("surefold/${schema.module}/postgres.sql") }
            val flyway = Postgres.empty().also { migrate(it, schema) }

            withClue("the two copies of ${schema.module}'s SQL have drifted apart") {
                catalog(flyway) shouldBe catalog(liquibase)
            }
        }

        test("a module runs after the ones it is listed before in SurefoldSchema, whatever the argument order") {
            val source = Postgres.empty()
            migrate(source, SurefoldSchema.JOBS, SurefoldSchema.TENANCY)

            val tenancyFirst = source.connection.use { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select (select max(installed_on) from flyway_surefold_tenancy) < " +
                            "(select min(installed_on) from flyway_surefold_jobs)",
                    ).use { rows -> rows.next() && rows.getBoolean(1) }
                }
            }
            withClue("tenancy's scripts were installed before any of jobs'") { tenancyFirst shouldBe true }
        }
    }
}
