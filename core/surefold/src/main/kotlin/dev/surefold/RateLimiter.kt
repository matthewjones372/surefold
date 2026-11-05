package dev.surefold

import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.ZERO
import kotlin.time.DurationUnit
import kotlin.time.toKotlinDuration

/**
 * Paces calls to [rate] every [per], with up to [burst] at once (spec 0113). A call that finds no token reserves the
 * next one and waits for it, up to [maxWait], and is refused when the wait would be longer. It is called through a
 * [Policy].
 */
class RateLimiter(
    val name: String,
    rate: Int,
    per: Duration,
    private val burst: Int = rate,
    internal val maxWait: Duration = ZERO,
) {
    init {
        require(rate >= 1 && per > ZERO) { "Rate limiter \"$name\" needs a rate of at least one per a positive time." }
        require(burst >= 1) { "Rate limiter \"$name\" must hold at least one token, not $burst." }
    }

    private val interval: Duration = per / rate

    // Tokens below zero are reservations already handed out, each waiting for its own token to arrive. Null until the
    // first call, so the bucket starts full by whichever clock that call is on.
    private data class Bucket(val tokens: Double, val at: Instant)

    private val bucket = AtomicReference<Bucket?>(null)

    internal sealed interface Reservation {
        data class Granted(val wait: Duration) : Reservation

        data class Refused(val retryAfter: Duration) : Reservation
    }

    /** A token now, or false. It never waits. */
    fun tryAcquire(): Boolean = reserve(cost = 1, maxWait = ZERO) is Reservation.Granted

    internal fun reserve(cost: Int, maxWait: Duration): Reservation =
        taken(cost, maxWait).also { reservation ->
            val outcome =
                when (reservation) {
                    is Reservation.Granted -> {
                        histogram("surefold.ratelimiter.wait", "name" to name)
                            .record(reservation.wait.toDouble(DurationUnit.SECONDS))
                        if (reservation.wait > ZERO) "waited" else "immediate"
                    }

                    is Reservation.Refused -> "rejected"
                }
            counter("surefold.ratelimiter.calls", "name" to name, "outcome" to outcome).increment()
        }

    private fun taken(cost: Int, maxWait: Duration): Reservation {
        while (true) {
            val seen = bucket.get()
            val now = clock.get().now()
            val left = refilled(seen, now) - cost
            val wait = if (left >= 0) ZERO else interval * -left
            if (wait > maxWait) return Reservation.Refused(wait)
            if (bucket.compareAndSet(seen, Bucket(left, now))) return Reservation.Granted(wait)
        }
    }

    internal fun refund(cost: Int) {
        bucket.updateAndGet { seen ->
            val now = clock.get().now()
            Bucket(minOf(burst.toDouble(), refilled(seen, now) + cost), now)
        }
    }

    private fun refilled(seen: Bucket?, now: Instant): Double =
        seen?.let {
            minOf(burst.toDouble(), it.tokens + java.time.Duration.between(it.at, now).toKotlinDuration() / interval)
        } ?: burst.toDouble()
}
