package dev.surefold.kafka

import kotlin.time.Duration

/**
 * What [Kafka.consume] does with the records it has handed out on a partition a rebalance takes away (spec 0158).
 * What was handled is committed either way; what was not is read again by the partition's new owner.
 */
class Rebalance private constructor(internal val within: Duration) {
    companion object {
        /** The rebalance goes on at once: a record handed out and not yet handled is read again by the new owner. */
        val immediately: Rebalance = Rebalance(Duration.ZERO)

        /**
         * The rebalance waits, up to [within], for each record handed out on a partition being taken away to be
         * handled, and commits them, so the new owner reads none of them again. The stream runs on meanwhile; only
         * the poll is held. A handler stuck past [within] holds the whole group's rebalance that long, and what it
         * has not handled by then is read again, as with [immediately].
         */
        fun waitFor(within: Duration): Rebalance {
            require(within.isPositive()) { "a rebalance that waits for $within waits for nothing" }
            return Rebalance(within)
        }
    }
}
