package dev.surefold.tenancy

import dev.surefold.logInfo
import java.sql.Connection
import java.sql.ResultSet
import javax.sql.DataSource

/**
 * The work that must cross tenants, on a role that holds no table grants and may only `EXECUTE` the `SECURITY DEFINER`
 * functions in [schema] (spec 0237). Postgres is the guarantee: whatever SQL ran on [source], its role could read no
 * row. Hiding the connection behind [FunctionCalls] keeps a service from writing that SQL by accident.
 */
class SystemFunctions(private val source: DataSource, private val schema: String) {
    init {
        checkIdentifier(schema)
    }

    /**
     * Runs [block] in one transaction on [source], with only [FunctionCalls] to reach the database through. Logs who
     * called and why; with [audited], also writes the row `TenantTransactions.system` writes, in the same transaction,
     * through `surefold_tenancy_audit_write`, so the role needs `EXECUTE` on that and no grant on the table.
     */
    fun <A> call(actor: String, reason: String, audited: Boolean = false, block: (FunctionCalls) -> A): A {
        require(actor.isNotBlank() && reason.isNotBlank()) { "A system call names who makes it, and why." }
        logInfo("surefold-tenancy: system call by $actor: $reason")
        return source.connection.use { connection ->
            inTransaction(connection) {
                if (audited) {
                    connection.prepareStatement(AUDIT).use { statement ->
                        statement.setString(1, actor)
                        statement.setString(2, reason)
                        statement.execute()
                    }
                }
                block(FunctionCalls(connection, schema))
            }
        }
    }

    private companion object {
        const val AUDIT = "select surefold_tenancy_audit_write(?, ?)"
    }
}

/** The functions of one schema, called by name with bound arguments: `select * from sys.resolve_host(?)`. */
class FunctionCalls internal constructor(private val connection: Connection, private val schema: String) {

    /** The one row [function] returns for [args], read by [read]; fails if it returns none or more than one. */
    fun <A> one(function: String, vararg args: Any?, read: (ResultSet) -> A): A =
        query(function, *args, read = read).let { rows ->
            check(rows.size == 1) { "$schema.$function returned ${rows.size} rows, not one." }
            rows.single()
        }

    /** The row [function] returns for [args], read by [read], or null if it returns none; fails on more than one. */
    fun <A> optional(function: String, vararg args: Any?, read: (ResultSet) -> A): A? =
        query(function, *args, read = read).let { rows ->
            check(rows.size <= 1) { "$schema.$function returned ${rows.size} rows, not one or none." }
            rows.singleOrNull()
        }

    /** Every row [function] returns for [args], each read by [read]. */
    fun <A> query(function: String, vararg args: Any?, read: (ResultSet) -> A): List<A> {
        checkIdentifier(function)
        val sql = "select * from $schema.$function(${args.joinToString { "?" }})"
        return connection.prepareStatement(sql).use { statement ->
            args.forEachIndexed { at, value -> statement.setObject(at + 1, value) }
            statement.executeQuery().use { rows -> buildList { while (rows.next()) add(read(rows)) } }
        }
    }
}

private val identifier = Regex("[a-z_][a-z0-9_]*")

private fun checkIdentifier(name: String) =
    require(identifier.matches(name)) { "A schema or function is named as an unquoted identifier: '$name'." }
