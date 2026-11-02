package dev.surefold.actor

import kotlin.time.Duration

/** Which queued message a test runtime delivers next. */
sealed interface Ordering {
    /** First in, first out: each message in the order it was told. */
    data object Told : Ordering

    /**
     * A pick, by a random source seeded with [seed], among each sender's oldest message to each receiver: interleavings
     * vary from seed to seed but stay legal, since remoting keeps one sender's messages to one receiver in order.
     */
    data class Seeded(val seed: Long) : Ordering

    companion object {
        fun seeded(seed: Long): Ordering = Seeded(seed)
    }
}

/** What happened to one message in a test runtime, in the order it happened, to read back when a test fails. */
sealed interface TraceEntry {
    /** [message] reached [to]; [from] is the actor whose turn told it, or null for the test itself. */
    data class Delivered(val from: Address?, val to: Address, val message: Any) : TraceEntry

    /** [message], told by [from] to [to], was lost. */
    data class Dropped(val from: Address, val to: Address, val message: Any) : TraceEntry

    /** [message], told by [from] to [to], is to arrive twice. */
    data class Duplicated(val from: Address, val to: Address, val message: Any) : TraceEntry

    /** [message], told by [from] to [to], is to arrive [by] later in the test's time. */
    data class Delayed(val from: Address, val to: Address, val message: Any, val by: Duration) : TraceEntry

    /** [actor] crashed: its mailbox and timers are gone, and its next message recovers it from the journal. */
    data class Crashed(val actor: Address) : TraceEntry
}
