package dev.surefold

import java.util.concurrent.CompletableFuture
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Runs [block] on a virtual thread of its own, with the caller's bindings as a fork has them, and answers with a future
 * whose `cancel` interrupts it: a caller that gives up stops the block's forks too.
 */
fun <A> surefoldFuture(block: () -> A): CompletableFuture<A> {
    val inherited = Bindings.snapshot()
    val future = Interrupting<A>()
    return future.completeAsync({ Bindings.under(inherited) { future.running(block) } }, VirtualThreads)
}

private class Interrupting<A> : CompletableFuture<A>() {

    // The thread and the interrupt aimed at it move under one lock, so a cancel landing as the block starts
    // is not lost between the two.
    private val lock = ReentrantLock()
    private var runner: Thread? = null

    fun running(block: () -> A): A {
        lock.withLock {
            runner = Thread.currentThread()
            if (isCancelled) Thread.currentThread().interrupt()
        }
        return try {
            block()
        } finally {
            lock.withLock { runner = null }
        }
    }

    // `CompletableFuture` ignores the flag, and kotlinx's `await` cancels with `false`, so the interrupt
    // does not depend on it.
    override fun cancel(mayInterruptIfRunning: Boolean): Boolean {
        val cancelled = super.cancel(mayInterruptIfRunning)
        if (cancelled) lock.withLock { runner?.interrupt() }
        return cancelled
    }

    // A `thenApply` on this future is a plain one: there is no block of surefold's behind it to interrupt.
    override fun <U> newIncompleteFuture(): CompletableFuture<U> = CompletableFuture()
}
