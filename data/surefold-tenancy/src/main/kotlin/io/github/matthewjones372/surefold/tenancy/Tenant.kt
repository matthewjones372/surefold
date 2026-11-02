package io.github.matthewjones372.surefold.tenancy

import dev.surefold.Carrier
import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal

/** Whose data a piece of work is about: a tenant's id, as the service names it. */
@JvmInline
value class TenantId(val value: String) {
    init {
        require(value.isNotBlank()) { "A tenant id cannot be blank." }
    }

    override fun toString(): String = value
}

/** The tenant the current work is for, or null. A fork inherits it, as it inherits the clock and the logger. */
val tenant: SurefoldLocal<TenantId?> = surefoldLocal { null }

/** Runs [block], and every fork it opens, as [id]'s, with no values bound. */
fun <A> withTenant(id: TenantId, block: () -> A): A = tenant.locally(id) { tenantValues.locally(emptyMap(), block) }

/** The values bound beside the tenant, by setting name: who is acting for it, and as what (spec 0237). */
internal val tenantValues: SurefoldLocal<Map<String, String>> = surefoldLocal { emptyMap() }

/**
 * Runs [block], and every fork it opens, as [id]'s, with [values] for the settings a [TenantTransactions] declares:
 * `"app.member_role" to "manager"`. A setting given no value here is set to its `absent`, and so is every setting
 * inside a nested `withTenant` that gives none: values never outlive the tenant they were bound with.
 */
fun <A> withTenant(id: TenantId, vararg values: Pair<String, String>, block: () -> A): A {
    values.forEach { (name, _) -> Setting.checkName(name) }
    return tenant.locally(id) { tenantValues.locally(values.toMap(), block) }
}

/** The tenant the current work is for, or null outside any [withTenant]. */
fun currentTenant(): TenantId? = tenant.get()

/**
 * Carries the tenant across a `tell` to an actor and into an enqueued job, beside the trace (spec 0225). Registered
 * through `META-INF/services`, so having this module on the classpath is enough.
 */
class TenantCarrier : Carrier {
    override fun capture(): Map<String, String> = tenant.get()?.let { mapOf(KEY to it.value) }.orEmpty()

    override fun <A> within(carried: Map<String, String>, block: () -> A): A =
        carried[KEY]?.let { withTenant(TenantId(it), block) } ?: block()

    private companion object {
        const val KEY = "surefold.tenant"
    }
}
