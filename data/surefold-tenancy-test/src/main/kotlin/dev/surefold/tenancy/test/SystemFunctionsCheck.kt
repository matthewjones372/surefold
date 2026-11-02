package dev.surefold.tenancy.test

import java.sql.Connection
import javax.sql.DataSource

/**
 * Checks that [role], the role `SystemFunctions` runs on, can reach rows only through the definer functions in
 * [expected] (spec 0237), named `schema.function`. Reads the catalog through [superuser], and throws [AssertionError],
 * one line per problem, when: the `SECURITY DEFINER` functions [role] may execute are not [expected]; one of them has
 * no pinned `search_path`; [role] holds any privilege on a table, view or sequence, or may create in a schema or the
 * database; or [role] is a superuser or has `BYPASSRLS`.
 */
fun checkSystemFunctions(superuser: DataSource, role: String, expected: Set<String>) {
    val problems = superuser.connection.use { connection ->
        val (isSuper, bypasses) = connection.rows(ATTRIBUTES, role) { it.getBoolean(1) to it.getBoolean(2) }
            .singleOrNull() ?: throw AssertionError("There is no role $role.")
        val functions = connection.rows(DEFINERS, role) { it.getString(1) to it.getBoolean(2) }.toMap()
        listOfNotNull(
            "$role is a superuser, so no grant or policy binds it.".takeIf { isSuper },
            "$role has BYPASSRLS, so it reads every tenant's rows.".takeIf { bypasses },
            (functions.keys - expected).sorted().takeIf { it.isNotEmpty() }
                ?.let { "$role may execute definer functions it is not expected to: ${it.joinToString()}." },
            (expected - functions.keys).sorted().takeIf { it.isNotEmpty() }
                ?.let { "$role cannot execute expected definer functions: ${it.joinToString()}." },
            functions.filterValues { pinned -> !pinned }.keys.sorted().takeIf { it.isNotEmpty() }
                ?.let { "No pinned search_path, so a caller's schema can shadow what they use: ${it.joinToString()}." },
            connection.rows(RELATIONS, role) { it.getString(1) }.takeIf { it.isNotEmpty() }
                ?.let { "$role holds privileges on tables or sequences: ${it.joinToString()}." },
            connection.rows(CREATES, role) { it.getString(1) }.takeIf { it.isNotEmpty() }
                ?.let { "$role may create in: ${it.joinToString()}." },
        )
    }
    if (problems.isNotEmpty()) throw AssertionError(problems.joinToString("\n"))
}

private fun <A> Connection.rows(sql: String, role: String, read: (java.sql.ResultSet) -> A): List<A> =
    prepareStatement(sql).use { statement ->
        (1..sql.count { it == '?' }).forEach { statement.setString(it, role) }
        statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
    }

private const val ATTRIBUTES = "select rolsuper, rolbypassrls from pg_roles where rolname = ?"

private const val OWN = "n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg\\_toast%'"

private const val DEFINERS = """
    select n.nspname || '.' || p.proname,
           exists (select 1 from unnest(p.proconfig) setting where setting like 'search\_path=%')
    from pg_proc p join pg_namespace n on n.oid = p.pronamespace
    where p.prosecdef and $OWN and has_function_privilege(?, p.oid, 'execute')
"""

private const val RELATIONS = """
    select n.nspname || '.' || c.relname
    from pg_class c join pg_namespace n on n.oid = c.relnamespace
    where $OWN and case
        when c.relkind = 'S' then has_sequence_privilege(?, c.oid, 'usage, select, update')
        when c.relkind in ('r', 'p', 'v', 'm', 'f') then
            has_table_privilege(?, c.oid, 'select, insert, update, delete, truncate, references, trigger')
                or has_any_column_privilege(?, c.oid, 'select, insert, update, references')
        else false
    end
    order by 1
"""

private const val CREATES = """
    select 'schema ' || n.nspname from pg_namespace n where $OWN and has_schema_privilege(?, n.oid, 'create')
    union all
    select 'database ' || current_database() where has_database_privilege(?, current_database(), 'create')
    order by 1
"""
