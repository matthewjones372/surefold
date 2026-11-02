package dev.surefold.tenancy

import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.logInfo
import java.sql.Connection
import java.time.ZoneOffset
import javax.sql.DataSource

/** The setting a row-level security policy reads the tenant from: `current_setting('app.tenant_id', true)`. */
const val TENANT_SETTING = "app.tenant_id"

/**
 * A setting every tenant transaction sets beside `app.tenant_id`, to the value bound with `withTenant`, or to
 * [absent] when none is, so a policy reading `current_setting(name)` never sees a pooled connection's last value.
 */
data class Setting(val name: String, val absent: String) {
    init {
        checkName(name)
    }

    internal companion object {
        private val shape = Regex("[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*")

        fun checkName(name: String) {
            require(shape.matches(name)) { "A setting is named word.word, as app.member_role is: '$name'." }
            require(name != TENANT_SETTING) { "$TENANT_SETTING is the tenant's, bound by withTenant's id." }
        }
    }
}

/** Why a tenant's transaction was not run. */
sealed interface TenancyError {
    /** No tenant was bound with `withTenant`, so there is no one whose rows the transaction could see. */
    data object NoTenant : TenancyError
}

/**
 * Transactions that Postgres scopes to one tenant. [app] is the application's own `DataSource`, whose role is subject
 * to row-level security: neither a superuser nor the owner of a table whose security is not forced. [system], if
 * given, reaches the database as a role with `BYPASSRLS`, for the work that must cross tenants, and nothing else.
 * Each of [settings] is set in every tenant transaction, beside the tenant.
 */
class TenantTransactions(
    private val app: DataSource,
    private val system: DataSource? = null,
    private val settings: List<Setting> = emptyList(),
) {
    init {
        require(settings.map { it.name }.toSet().size == settings.size) { "A setting is declared once." }
    }

    private val setAll = (listOf(TENANT_SETTING) + settings.map { it.name })
        .joinToString(prefix = "select ") { "set_config(?, ?, true)" }

    /**
     * Runs [block] in a transaction on [app] as the bound tenant's: `app.tenant_id` and each declared setting are set
     * for that transaction only, as `SET LOCAL` sets them, so a pooled connection carries no tenant and no value to
     * its next user. A value bound for a setting not declared here is not set. Commits when [block]
     * returns, and rolls back when it throws. With no tenant bound, answers [TenancyError.NoTenant] and opens nothing.
     */
    fun <A> transaction(block: (Connection) -> A): Either<TenancyError, A> {
        val id = currentTenant() ?: return TenancyError.NoTenant.left()
        return app.connection.use { connection ->
            inTransaction(connection) {
                val bound = tenantValues.get()
                val pairs =
                    listOf(TENANT_SETTING to id.value) + settings.map { it.name to (bound[it.name] ?: it.absent) }
                connection.prepareStatement(setAll).use { statement ->
                    pairs.forEachIndexed { at, (name, value) ->
                        statement.setString(2 * at + 1, name)
                        statement.setString(2 * at + 2, value)
                    }
                    statement.execute()
                }
                block(connection)
            }
        }.right()
    }

    /** [transaction] as [id]'s, binding it for [block] and every fork [block] opens. */
    fun <A> transaction(id: TenantId, block: (Connection) -> A): A =
        withTenant(id) { (transaction(block) as Either.Right).value }

    /** [transaction] as [id]'s, with [values], binding them for [block] and every fork [block] opens. */
    fun <A> transaction(id: TenantId, vararg values: Pair<String, String>, block: (Connection) -> A): A =
        withTenant(id, *values) { (transaction(block) as Either.Right).value }

    /**
     * Runs [block] across every tenant, on [system], and records who ran it and why in `surefold_tenancy_audit`, in
     * the same transaction, and as a log line. For the jobs worker, migrations of data, and an operator's support
     * work; never for a request.
     */
    fun <A> system(actor: String, reason: String, block: (Connection) -> A): A {
        val source = checkNotNull(system) { "TenantTransactions was made without a system DataSource." }
        require(actor.isNotBlank() && reason.isNotBlank()) { "A system transaction names who runs it, and why." }
        logInfo("surefold-tenancy: system transaction by $actor: $reason")
        return source.connection.use { connection ->
            inTransaction(connection) {
                connection.prepareStatement(AUDIT).use { statement ->
                    listOf(actor, reason, clock.get().now().atOffset(ZoneOffset.UTC))
                        .forEachIndexed { at, value -> statement.setObject(at + 1, value) }
                    statement.executeUpdate()
                }
                block(connection)
            }
        }
    }

    private companion object {
        const val AUDIT = "insert into surefold_tenancy_audit (actor, reason, at) values (?, ?, ?)"
    }
}

/** Runs [block] in a transaction on [connection]: commits when it returns, rolls back when it throws. */
internal fun <A> inTransaction(connection: Connection, block: () -> A): A {
    val wasAuto = connection.autoCommit
    connection.autoCommit = false
    var committed = false
    try {
        return block().also {
            connection.commit()
            committed = true
        }
    } finally {
        if (!committed) connection.rollback()
        connection.autoCommit = wasAuto
    }
}
