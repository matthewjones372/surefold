package dev.surefold.app.flyway

/**
 * A Surefold data module's Flyway scripts, which its own jar ships, and the history table they keep: one per module,
 * so a module's versions never meet a service's or another module's. Declared in the order they run.
 */
enum class SurefoldSchema(internal val module: String) {
    TENANCY("tenancy"),
    TENANCY_CRYPTO("tenancy-crypto"),
    JOBS("jobs"),
    IDEMPOTENCY("idempotency"),
    RATELIMIT("ratelimit"),
    AUTH("auth"),
    OPENFEATURE_POSTGRES("openfeature-postgres"),
    ;

    internal val location: String get() = "classpath:surefold/$module/flyway"

    internal val table: String get() = "flyway_surefold_$module"
}
