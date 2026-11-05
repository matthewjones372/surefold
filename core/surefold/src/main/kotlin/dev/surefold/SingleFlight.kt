package dev.surefold

import dev.surefold.fail.Either
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One call per key while it is in flight (spec 0247): the first [run] for a key starts its block, and every [run] for
 * that key until it ends waits on it and is answered the same. Nothing is remembered once it ends; remembering is a
 * cache's. The block runs on a virtual thread of its own with the first caller's bindings, so a caller that stops
 * waiting stops only itself, and the block still answers the rest.
 */
class SingleFlight<K : Any, E, V>(private val name: String = "single-flight") {
    private val flights = ConcurrentHashMap<K, Promise<Throwable, Either<E, V>>>()
    private val admission = ReentrantLock()
    private var workers: List<Thread> = emptyList()
    private var closing: List<Thread>? = null

    /** [block]'s answer for [key], from this call's run of it or the one in flight. A throw reaches every waiter. */
    fun run(key: K, block: () -> Either<E, V>): Either<E, V> {
        val flight = admission.withLock {
            check(closing == null) { "SingleFlight is closed" }
            flights[key]
                ?: Promise<Throwable, Either<E, V>>(name).also {
                    fly(key, it, block)
                }
        }
        return when (val ended = flight.await()) {
            is Either.Right -> ended.value
            is Either.Left -> throw ended.value
        }
    }

    /** How many keys have a call in flight now. */
    fun inFlight(): Int = flights.size

    /**
     * Interrupts every block in flight, for an owner that is closing: each ends at its next interruptible call, and its
     * waiters are answered with what it throws then.
     */
    fun cancelAll() {
        admission.withLock { workers.forEach { it.interrupt() } }
    }

    /** Closes admission and joins every accepted worker, finishing even if interrupted, then restoring interruption. */
    fun closeAndJoin() {
        val owned = admission.withLock {
            check(workers.none { it === Thread.currentThread() }) { "A SingleFlight worker cannot close its owner" }
            closing
                ?: workers.also { accepted ->
                    closing = accepted
                    accepted.forEach { it.interrupt() }
                }
        }
        var interrupted = Thread.interrupted()
        try {
            owned.forEach { worker ->
                while (worker.isAlive) {
                    try {
                        worker.join()
                    } catch (_: InterruptedException) {
                        interrupted = true
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    /** How many callers are parked on [key]'s flight now: none once it has ended, or before one starts. */
    fun waiting(key: K): Int = flights[key]?.waiting() ?: 0

    @Suppress("TooGenericExceptionCaught") // Startup and lookup failures belong to the callers, not this boundary.
    private fun fly(key: K, flight: Promise<Throwable, Either<E, V>>, block: () -> Either<E, V>) {
        val inherited = Bindings.snapshot()
        // Not named by the key, which may hold user data, and which a thread dump would then print (spec 0308).
        val worker =
            Thread.ofVirtual().name("surefold-single-flight").unstarted {
                Bindings.under(inherited) {
                    val ended: Either<Throwable, Either<E, V>> =
                        try {
                            Either.Right(block())
                        } catch (thrown: Throwable) {
                            Either.Left(thrown)
                        }
                    // A cancel that arrived as the block ended is not the answer's to carry.
                    Thread.interrupted()
                    // Free the key first, so a call that sees this answer and comes back runs the block again.
                    flights.remove(key, flight)
                    when (ended) {
                        is Either.Right -> flight.succeed(ended.value)
                        is Either.Left -> flight.fail(ended.value)
                    }
                }
            }
        // Keep ownership past answer publication; only a terminated thread can be forgotten safely.
        workers = workers.filter { it.isAlive } + worker
        try {
            flights[key] = flight
            worker.start()
        } catch (thrown: Throwable) {
            flights.remove(key, flight)
            workers = workers.filter { it !== worker }
            flight.fail(thrown)
            throw thrown
        }
    }
}
