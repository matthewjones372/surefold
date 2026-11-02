package dev.surefold.cluster

import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a partition is resolved. Nothing is decided until the members and who is unreachable have not changed for
 * [stableAfter]; then every node applies the same rule to its side. A side that stays downs the members it cannot
 * reach, and removes them [stableAfter] later; a node on a side that does not stay downs itself at once, so the two
 * never overlap.
 */
sealed interface Downing {
    val stableAfter: Duration

    /** The side with more than half of the members stays; on an even split, the side with the lowest address. */
    data class KeepMajority(override val stableAfter: Duration) : Downing

    /** A side of at least [size] members stays. With [size] over half of the cluster, at most one side does. */
    data class StaticQuorum(val size: Int, override val stableAfter: Duration) : Downing

    /** The side that acquires [lease] stays: for two nodes or an even split, where a majority cannot decide. */
    data class ByLease(val lease: Lease, override val stableAfter: Duration) : Downing

    companion object {
        fun keepMajority(stableAfter: Duration = 20.seconds): Downing = KeepMajority(stableAfter)

        fun staticQuorum(size: Int, stableAfter: Duration = 20.seconds): Downing = StaticQuorum(size, stableAfter)

        fun lease(lease: Lease, stableAfter: Duration = 20.seconds): Downing = ByLease(lease, stableAfter)
    }
}

/**
 * A lock outside the cluster that one holder has at a time, such as a Kubernetes `Lease`. Every node of a side asks
 * for it under the same [holder], its side's lowest address, so it answers true again to the holder it already has.
 */
fun interface Lease {
    fun acquire(holder: String): Boolean
}
