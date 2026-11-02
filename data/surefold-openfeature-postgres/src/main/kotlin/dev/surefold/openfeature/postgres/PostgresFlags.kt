package dev.surefold.openfeature.postgres

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.EventProvider
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.ProviderEvaluation
import dev.openfeature.sdk.ProviderEventDetails
import dev.openfeature.sdk.Reason
import dev.openfeature.sdk.Value
import dev.surefold.cache.Cache
import dev.surefold.cache.Invalidation
import dev.surefold.fail.Either
import dev.surefold.postgres.notify.Notice
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.runFold
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.TenantTransactions
import dev.surefold.tenancy.currentTenant
import dev.surefold.tenancy.withTenant
import java.sql.Connection
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** The evaluation context attribute a flag is read for before the bound tenant, so a tool can ask as any tenant. */
const val TENANT_ATTRIBUTE = "tenant"

/** The channel the table's trigger notifies on, with a row's tenant, or '' for a row every tenant gets. */
const val FLAGS_CHANNEL = "surefold_feature_flags"

/**
 * Flags per tenant from `surefold_feature_flags` (spec 0240), read on [app], the application's own `DataSource`,
 * under the table's row-level security policy. A flag answers, in order:
 * - the tenant's row, with the reason `TARGETING_MATCH`;
 * - the row every tenant gets, whose `tenant_id` is null, with the reason `STATIC`;
 * - [fallback]'s answer, as it gives it, a `FlagNotFoundError` for a flag it does not hold included;
 * - the caller's default.
 *
 * The tenant is the context's [TENANT_ATTRIBUTE] if set, else the bound one; with neither, only null rows answer. A
 * row's text is read as the type asked for, and one that does not parse answers the default with `PARSE_ERROR`.
 * There are no object flags: an object is [fallback]'s, or the default. [fallback] starts and stops with this provider.
 *
 * A scope's rows, a tenant's or none's, are read once per [ttl] on 0246's `Cache`, on the clock bound here, however
 * many evaluate at once. While the table cannot be read, the last rows read answer for up to [stale] and the provider
 * reports `STALE`; past that, or with nothing read yet, flags answer as if there were no rows and it reports `ERROR`.
 */
class PostgresFlags(
    private val app: DataSource,
    private val fallback: FeatureProvider? = null,
    ttl: Duration = 30.seconds,
    stale: Duration = Duration.ZERO,
) : EventProvider() {
    private val tenants = TenantTransactions(app)

    // Whether the last read reached the table, and what the provider last told the SDK it was.
    private val reached = AtomicBoolean(true)
    private val told = AtomicReference(Told.READY)

    private val scopes = Cache<Scope, SQLException, Map<String, Row>>(
        ttl = { read -> if (read is Either.Right) ttl else minOf(ttl, FAILED_FOR) },
        stale = stale,
    ) { scope ->
        try {
            Either.Right(rows(scope.tenant?.let(::TenantId))).also { reached.set(true) }
        } catch (failed: SQLException) {
            reached.set(false)
            Either.Left(failed)
        }
    }

    override fun getMetadata() = Metadata { "surefold-postgres" }

    // The SDK starts and stops only the provider it was given, so the fallback is started and stopped with this one.
    // It starts a provider with no context at all when none was set, so the context is nullable here.
    override fun initialize(evaluationContext: EvaluationContext?) {
        fallback?.initialize(evaluationContext)
    }

    override fun shutdown() {
        scopes.close()
        fallback?.shutdown()
        super.shutdown()
    }

    /**
     * Sets [key] to [value] for [tenant], or for every tenant with none, on [connection], whose role may write the
     * table, and drops what this provider holds for it at once. Another process sees it after its own `ttl`. In
     * autocommit the row is written when this returns; in the caller's transaction, call it once that has committed.
     */
    fun set(connection: Connection, key: String, tenant: TenantId?, value: String, reason: String, by: String) {
        connection.prepareStatement(UPSERT).use { statement ->
            val values = listOf(key, tenant?.value, value, reason, by)
            values.forEachIndexed { at, text -> statement.setString(at + 1, text) }
            statement.executeUpdate()
        }
        forget(tenant)
    }

    /** Removes [tenant]'s row for [key], or the row every tenant gets with none, as [set] writes one. */
    fun clear(connection: Connection, key: String, tenant: TenantId?) {
        connection.prepareStatement(DELETE).use { statement ->
            statement.setString(1, key)
            statement.setString(2, tenant?.value)
            statement.executeUpdate()
        }
        forget(tenant)
    }

    /**
     * Drops what this provider holds as the table's trigger reports each change, for as long as the run lasts, so a
     * row changed by another process, or by hand, is seen before the ttl runs out. [notices] is
     * `PostgresNotifications.listen(source, setOf(FLAGS_CHANNEL))`: a tenant's row drops that tenant's scope, and a
     * row every tenant gets, or a [Notice.Gap], drops every scope. A notice on another channel, from a listener this
     * shares, drops nothing. The run answers how many it applied.
     */
    fun listening(notices: Stream<Nothing, Notice>): Run<Nothing, Long> = notices.runFold(0L) { applied, notice ->
        val dropped = dropping(notice) ?: return@runFold applied
        scopes.invalidate(dropped)
        applied + 1
    }

    private fun dropping(notice: Notice): Invalidation<Scope>? = when (notice) {
        Notice.Gap -> Invalidation.All

        is Notice.Received -> notice.takeIf { it.channel == FLAGS_CHANNEL }?.let {
            ofTenant(it.payload.takeIf(String::isNotEmpty)?.let(::TenantId))
        }
    }

    // A null row is every scope's, so its change drops them all.
    private fun forget(tenant: TenantId?) = scopes.invalidate(ofTenant(tenant))

    private fun ofTenant(tenant: TenantId?): Invalidation<Scope> =
        if (tenant == null) Invalidation.All else Invalidation.Key(Scope(tenant.value))

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a boolean", { it.toBooleanStrictOrNull() }) {
            fallback?.getBooleanEvaluation(key, defaultValue, ctx)
        }

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a string", { it }) { fallback?.getStringEvaluation(key, defaultValue, ctx) }

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "an integer", { it.toIntOrNull() }) {
            fallback?.getIntegerEvaluation(key, defaultValue, ctx)
        }

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) =
        evaluate(key, defaultValue, ctx, "a double", { it.toDoubleOrNull() }) {
            fallback?.getDoubleEvaluation(key, defaultValue, ctx)
        }

    override fun getObjectEvaluation(key: String, defaultValue: Value, ctx: EvaluationContext) =
        fallback?.getObjectEvaluation(key, defaultValue, ctx) ?: answer(defaultValue, Reason.DEFAULT)

    private fun <A> evaluate(
        key: String,
        default: A,
        ctx: EvaluationContext,
        type: String,
        parse: (String) -> A?,
        fallen: () -> ProviderEvaluation<A>?,
    ): ProviderEvaluation<A> {
        val row = read(Scope(scopeOf(ctx)?.value))[key] ?: return fallen() ?: answer(default, Reason.DEFAULT)
        val parsed = parse(row.value) ?: return ProviderEvaluation.builder<A>()
            .value(default)
            .reason(Reason.ERROR.name)
            .errorCode(ErrorCode.PARSE_ERROR)
            .errorMessage("$key is '${row.value}', which is not $type")
            .build()
        return answer(parsed, if (row.tenants) Reason.TARGETING_MATCH else Reason.STATIC)
    }

    private fun scopeOf(ctx: EvaluationContext): TenantId? =
        ctx.getValue(TENANT_ATTRIBUTE)?.asString()?.takeIf { it.isNotBlank() }?.let(::TenantId) ?: currentTenant()

    // The scope's rows from the cache, telling the SDK when that answer moved between fresh, stale and none.
    private fun read(scope: Scope): Map<String, Row> {
        val answered = scopes.get(scope)
        val now = when {
            answered is Either.Left -> Told.ERROR
            reached.get() -> Told.READY
            else -> Told.STALE
        }
        if (told.getAndSet(now) != now) {
            val details = ProviderEventDetails.builder().message("the feature flags table: $now").build()
            when (now) {
                Told.READY -> emitProviderReady(details)
                Told.STALE -> emitProviderStale(details)
                Told.ERROR -> emitProviderError(details)
            }
        }
        return (answered as? Either.Right)?.value.orEmpty()
    }

    // The tenant's rows and the null rows in one query, each key's own row before the null one.
    private fun rows(tenant: TenantId?): Map<String, Row> {
        val read = if (tenant == null) {
            everyone()
        } else {
            when (val answered = withTenant(tenant) { tenants.transaction(::select) }) {
                is Either.Right -> answered.value
                is Either.Left -> error("a tenant was bound, and no tenant transaction ran: ${answered.value}")
            }
        }
        return read.sortedBy { it.tenants }.associateBy { it.key }
    }

    // No tenant: a transaction with app.tenant_id cleared, so a pooled connection's session value is not read as one.
    private fun everyone(): List<Row> = app.connection.use { connection ->
        val auto = connection.autoCommit
        connection.autoCommit = false
        try {
            connection.prepareStatement("select set_config('app.tenant_id', '', true)").use { it.execute() }
            select(connection).also { connection.commit() }
        } catch (failed: SQLException) {
            connection.rollback()
            throw failed
        } finally {
            connection.autoCommit = auto
        }
    }

    private fun select(connection: Connection): List<Row> = connection.prepareStatement(SELECT).use { statement ->
        statement.executeQuery().use { rows ->
            generateSequence {
                if (!rows.next()) return@generateSequence null
                Row(rows.getString("key"), rows.getString("value"), rows.getBoolean("tenants"))
            }.toList()
        }
    }

    private class Row(val key: String, val value: String, val tenants: Boolean)

    // Whose rows: a tenant's and the null ones, or the null ones alone.
    private data class Scope(val tenant: String?)

    private enum class Told { READY, STALE, ERROR }

    private companion object {
        // The policy decides what a tenant sees; the condition keeps the same answer on a role it does not bind.
        const val SELECT = """
            select key, value, tenant_id is not null as tenants from surefold_feature_flags
            where tenant_id is null or tenant_id = current_setting('app.tenant_id', true)
        """

        // How long a failed read is kept before the table is tried again, at most the ttl.
        val FAILED_FOR = 1.seconds

        const val UPSERT = """
            insert into surefold_feature_flags (key, tenant_id, value, reason, updated_by, updated_at)
            values (?, ?, ?, ?, ?, now())
            on conflict (key, (coalesce(tenant_id, ''))) do update
            set value = excluded.value, reason = excluded.reason, updated_by = excluded.updated_by, updated_at = now()
        """

        const val DELETE = "delete from surefold_feature_flags where key = ? and tenant_id is not distinct from ?"

        fun <A> answer(value: A, reason: Reason): ProviderEvaluation<A> =
            ProviderEvaluation.builder<A>().value(value).reason(reason.name).build()
    }
}
