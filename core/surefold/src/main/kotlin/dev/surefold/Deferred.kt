package dev.surefold

/** A fork's answer, asked for once it is wanted, or given up on where it is no longer worth waiting for. */
interface Deferred<T> {
    /** Joins the fork, then returns its value, raises its error into the awaiting scope, or rethrows its throwable. */
    fun await(): T

    /**
     * Interrupts the fork and returns once it has ended, so nothing it owns outlives this call. Its outcome counts as
     * noticed either way: a caller that gave up on a fork is not answered by it at scope close.
     */
    fun cancel()
}
