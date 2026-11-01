package io.github.matthewjones372.surefold

/** Clears interruption during cleanup and restores it on return, throw or a non-local return. */
inline fun <A> draining(block: () -> A): A {
    var interrupted = Thread.interrupted()
    try {
        return block()
    } catch (failure: InterruptedException) {
        interrupted = true
        throw failure
    } finally {
        if (interrupted || Thread.interrupted()) Thread.currentThread().interrupt()
    }
}

/** Repeats an interruption-safe wait until it returns, restoring interruption and propagating other failures. */
inline fun <A> uninterruptible(
    crossinline onInterrupted: (InterruptedException) -> Unit = {},
    block: () -> A,
): A {
    var interrupted = Thread.interrupted()
    try {
        while (true) {
            try {
                return block()
            } catch (failure: InterruptedException) {
                interrupted = true
                onInterrupted(failure)
            }
        }
    } finally {
        if (interrupted || Thread.interrupted()) Thread.currentThread().interrupt()
    }
}

/** Observes any escaping throw for cleanup, preserving its identity and suppressing observer failures onto it. */
@Suppress("TooGenericExceptionCaught") // observes every escaping throw without changing its control flow
inline fun <A> onThrow(crossinline action: (Throwable) -> Unit, block: () -> A): A =
    try {
        block()
    } catch (failure: Throwable) {
        try {
            action(failure)
        } catch (observer: Throwable) {
            if (observer !== failure) failure.addSuppressed(observer)
        }
        throw failure
    }

/** Stops synchronous adapter work at its next cancellation checkpoint without clearing the interrupt flag. */
fun checkInterrupted() {
    if (Thread.currentThread().isInterrupted) throw InterruptedException("interrupted at a cancellation checkpoint")
}
