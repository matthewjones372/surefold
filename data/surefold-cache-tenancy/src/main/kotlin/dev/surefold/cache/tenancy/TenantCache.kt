package dev.surefold.cache.tenancy

import dev.surefold.Schedule
import dev.surefold.cache.Cache
import dev.surefold.cache.CacheStats
import dev.surefold.cache.DEFAULT_CAPACITY
import dev.surefold.cache.Invalidation
import dev.surefold.cache.Layer
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.tenancy.TenancyError
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.currentTenant
import dev.surefold.tenancy.withTenant
import kotlin.time.Duration

/** A key as a tenant cache keeps it, in memory and in every layer: one tenant's, and never another's. */
data class TenantKey<K : Any>(val tenant: TenantId, val key: K)

/**
 * A [Cache] whose every entry is one tenant's (spec 0246): [get] keys by the bound tenant, so two tenants asking for
 * one key each look up their own value, and with no tenant bound it answers [TenancyError.NoTenant] rather than an
 * entry no tenant owns. [lookup] runs as the key's tenant, a refresh in the background included, so its own tenant
 * transactions see that tenant's rows. Everything else is the [Cache]'s, as it documents.
 */
class TenantCache<K : Any, E, V>(
    ttl: (Either<E, V>) -> Duration,
    stale: Duration = Duration.ZERO,
    capacity: Int = DEFAULT_CAPACITY,
    onEvict: (TenantKey<K>, V) -> Unit = { _, _ -> },
    layers: List<Layer<TenantKey<K>, V>> = emptyList(),
    retry: Schedule<E, *>? = null,
    refreshing: Schedule<Either<E, V>, *>? = null,
    lookup: (K) -> Either<E, V>,
) : AutoCloseable {
    private val cache = Cache(ttl, stale, capacity, onEvict, layers, retry, refreshing) { at: TenantKey<K> ->
        withTenant(at.tenant) { lookup(at.key) }
    }

    /** The bound tenant's answer for [key], as [Cache.get] answers it, or [TenancyError.NoTenant] with none bound. */
    fun get(key: K): Either<TenancyError, Either<E, V>> {
        val tenant = currentTenant() ?: return TenancyError.NoTenant.left()
        return cache.get(TenantKey(tenant, key)).right()
    }

    /** Looks the bound tenant's [key] up again in the background; with no tenant bound, there is nothing to refresh. */
    fun refresh(key: K): Either<TenancyError, Unit> {
        val tenant = currentTenant() ?: return TenancyError.NoTenant.left()
        return cache.refresh(TenantKey(tenant, key)).right()
    }

    /** Forgets one tenant's key, or every tenant's every key. */
    fun invalidate(what: Invalidation<TenantKey<K>>) = cache.invalidate(what)

    fun stats(): CacheStats = cache.stats()

    override fun close() = cache.close()
}
