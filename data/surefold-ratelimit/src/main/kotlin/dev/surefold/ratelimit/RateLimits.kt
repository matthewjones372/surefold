package dev.surefold.ratelimit

import dev.surefold.clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.floor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.toKotlinDuration

/** [rate] tokens every [per], held up to [burst]: a full bucket answers [burst] takes at once. */
data class TokenBucket(val rate: Int, val per: Duration, val burst: Int = rate) {
    init {
        require(rate >= 1 && per > ZERO) { "A bucket refills one token or more a positive time, not $rate per $per." }
        require(burst >= 1) { "A bucket holds at least one token, not $burst." }
    }

    internal val perSecond: Double get() = rate / per.toDouble(DurationUnit.SECONDS)

    /** What a bucket that held [tokens] at [since] holds at [now], never more than [burst]. */
    internal fun refilled(tokens: Double, since: Instant, now: Instant): Double {
        val elapsed = java.time.Duration.between(since, now).toKotlinDuration().coerceAtLeast(ZERO)
        return minOf(burst.toDouble(), tokens + elapsed.toDouble(DurationUnit.SECONDS) * perSecond)
    }

    /** How long until a bucket holding [tokens] holds [cost]. */
    internal fun waitFor(tokens: Double, cost: Int): Duration = ((cost - tokens) / perSecond).coerceAtLeast(0.0).seconds
}

/** What a take answered. */
sealed interface Decision {
    /** Go ahead; [remaining] whole tokens are left. */
    data class Allowed(val remaining: Long) : Decision

    /** Not now; the tokens asked for are there after [retryAfter], as a `Retry-After` would say. */
    data class Denied(val retryAfter: Duration) : Decision
}

/** Token buckets by key: a client, a tenant, an endpoint, or any of them together. */
interface RateLimits {
    /** Takes [cost] tokens from [key]'s [bucket], made full on first use, or says how long until it can. */
    fun take(key: String, bucket: TokenBucket, cost: Int = 1): Decision
}

/** [RateLimits] in this process, for a single instance or a test. */
class InMemoryRateLimits : RateLimits {
    private class Held(val tokens: Double, val at: Instant)

    private val held = ConcurrentHashMap<String, Held>()

    override fun take(key: String, bucket: TokenBucket, cost: Int): Decision {
        require(cost >= 1) { "A take costs at least one token, not $cost." }
        val now = clock.get().now()
        var decision: Decision = Decision.Denied(ZERO)
        held.compute(key) { _, before ->
            val tokens = before?.let { bucket.refilled(it.tokens, it.at, now) } ?: bucket.burst.toDouble()
            if (tokens >= cost) {
                decision = Decision.Allowed(floor(tokens - cost).toLong())
                Held(tokens - cost, maxOf(now, before?.at ?: now))
            } else {
                decision = Decision.Denied(bucket.waitFor(tokens, cost))
                Held(tokens, maxOf(now, before?.at ?: now))
            }
        }
        return decision
    }
}
