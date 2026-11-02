package dev.surefold.stream

/** How a run ended. `run` completes normally with one of these three, never with a failed stage. */
sealed interface Exit<out E, out A> {

    data class Done<A : Any>(val value: A) : Exit<Nothing, A>

    data class Failed<E>(val error: E) : Exit<E, Nothing>

    /** A throwable nobody declared: a bug, handed back to be matched on rather than thrown again. */
    data class Died(val cause: Throwable) : Exit<Nothing, Nothing>
}
