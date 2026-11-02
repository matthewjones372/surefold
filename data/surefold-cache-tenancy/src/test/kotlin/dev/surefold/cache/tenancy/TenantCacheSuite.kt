package dev.surefold.cache.tenancy

import dev.surefold.cache.Invalidation
import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.tenancy.TenancyError
import dev.surefold.tenancy.TenantId
import dev.surefold.tenancy.currentTenant
import dev.surefold.tenancy.withTenant
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/** Every entry one tenant's: two tenants never share one, and with no tenant there is none to answer (spec 0246). */
object TenantCacheSuite : Suite() {
    private val acme = TenantId("acme")
    private val globex = TenantId("globex")

    init {
        test("two tenants reading one key each look up their own value, as their own tenant") {
            val lookups = AtomicInteger()
            val settings = TenantCache<String, Nothing, String>({ 1.hours }) { key ->
                lookups.incrementAndGet()
                Either.Right("$key for ${currentTenant()}")
            }
            withTenant(acme) { settings.get("theme") } shouldBe Either.Right(Either.Right("theme for acme"))
            withTenant(globex) { settings.get("theme") } shouldBe Either.Right(Either.Right("theme for globex"))
            withTenant(acme) { settings.get("theme") } shouldBe Either.Right(Either.Right("theme for acme"))
            lookups.get() shouldBe 2
            settings.close()
        }

        test("with no tenant bound, get and refresh answer NoTenant, and no lookup runs") {
            val lookups = AtomicInteger()
            val settings = TenantCache<String, Nothing, String>({ 1.hours }) { key ->
                lookups.incrementAndGet()
                Either.Right(key)
            }
            settings.get("theme") shouldBe Either.Left(TenancyError.NoTenant)
            settings.refresh("theme") shouldBe Either.Left(TenancyError.NoTenant)
            lookups.get() shouldBe 0
            settings.close()
        }

        test("one tenant's key is invalidated without touching another's") {
            val lookups = AtomicInteger()
            val settings = TenantCache<String, Nothing, Int>({ 1.hours }) { Either.Right(lookups.incrementAndGet()) }
            withTenant(acme) { settings.get("k") }
            withTenant(globex) { settings.get("k") }
            settings.invalidate(Invalidation.Key(TenantKey(acme, "k")))
            withTenant(acme) { settings.get("k") } shouldBe Either.Right(Either.Right(3))
            withTenant(globex) { settings.get("k") } shouldBe Either.Right(Either.Right(2))
            settings.close()
        }
    }
}
