package dev.surefold.actor

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * What a test runtime does to a message one actor tells another, each by probability from the order's seed: lose it,
 * deliver it twice, deliver it up to [maxDelay] later in the test's time, or [crash] the actor it was for, which loses
 * it too. A delayed message holds back what its sender tells the same receiver after it, as one connection would. A
 * tell from the test, or from an actor to itself, is never touched, and nor are timers.
 */
data class Faults(
    val drop: Double = 0.0,
    val duplicate: Double = 0.0,
    val delay: Double = 0.0,
    val maxDelay: Duration = 1.seconds,
    val crash: Double = 0.0,
) {
    init {
        listOf(drop, duplicate, delay, crash).forEach {
            require(it in 0.0..1.0) { "a probability is from 0 to 1, not $it" }
        }
        require(drop + duplicate + delay + crash <= 1.0) {
            "drop, duplicate, delay and crash add up to more than 1"
        }
        require(maxDelay.isPositive()) { "maxDelay must be positive, was $maxDelay" }
    }

    internal val any: Boolean get() = drop + duplicate + delay + crash > 0.0
}
