package dev.surefold.ratelimit

import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.actor.journal.jdbc.migrate

/** The Postgres buckets keep the contract the in-memory ones keep. */
object PostgresRateLimitsSuite : RateLimitsContract() {
    override fun limits(): RateLimits = PostgresRateLimits(
        Postgres.empty().also { source ->
            source.migrate("surefold/ratelimit/postgres.sql")
        },
    )
}
