package dev.surefold.ratelimit

import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.actor.journal.jdbc.migrate

/** The Postgres buckets keep the contract the in-memory ones keep. */
object PostgresRateLimitsSuite : RateLimitsContract() {
    override fun limits(): RateLimits = PostgresRateLimits(
        Postgres.empty().also { source ->
            source.migrate("surefold/ratelimit/postgres.sql")
        },
    )
}
