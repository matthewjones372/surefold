package dev.surefold.stream

import dev.surefold.logWarn
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A resource opened once per run and read by blocking: a consumer's `poll`, a cursor, a queue's `take`.
 *
 * [next] is called by one thread at a time and `null` ends the stream. [wake] is how a run's `stop()` reaches a [next]
 * that is blocked, from any thread; a [next] it interrupts may return `null` or throw, and either ends the stream.
 * [close] runs exactly once, after the last [next], however the run ended.
 */
fun <R : Any, A : Any> Stream.Companion.blocking(
    open: () -> R,
    next: (R) -> A?,
    wake: (R) -> Unit,
    close: (R) -> Unit,
): Stream<Nothing, A> = Stream(Node.Blocking(open, next.erased(), wake.erased(), close.erased(), buildSite()))

/**
 * [blocking], with [ready] saying when the next [next] returns without blocking: a consumer's last poll still holding
 * records, a cursor's fetched rows. A backend that hands each element to the stream across threads reads the ready ones
 * along with the read before them and hands them over together, as Pekko does (spec 0154); one that reads on the
 * stream's own thread has nothing to save and never asks. [ready] is asked between reads, by the thread that reads, so
 * it sees what [next] left.
 */
fun <R : Any, A : Any> Stream.Companion.blocking(
    open: () -> R,
    next: (R) -> A?,
    ready: (R) -> Boolean,
    wake: (R) -> Unit,
    close: (R) -> Unit,
): Stream<Nothing, A> =
    Stream(Node.Blocking(open, next.erased(), wake.erased(), close.erased(), buildSite(), ready.erased()))

/**
 * One run's resource, for a backend: the rules [Stream.Companion.blocking] promises, kept in one place so every backend
 * keeps them the same way.
 */
@StreamSpi
class Opened internal constructor(private val node: Node.Blocking, private val readEndsWithin: Duration) {

    constructor(node: Node.Blocking) : this(node, READ_ENDS_WITHIN)

    private val resource: Any = node.open()
    private val woken = AtomicBoolean(false)
    private val closed = AtomicBoolean(false)

    /** Held for each read, so a close from another thread waits for the read it would close under. */
    private val reading = ReentrantLock()

    /** The next element, or `null` once the resource is done or the run was woken to stop. */
    // Whatever a woken next throws is how it was told to stop, so it ends the stream rather than the run.
    @Suppress("TooGenericExceptionCaught")
    fun next(): Any? = reading.withLock {
        if (woken.get() || closed.get()) {
            null
        } else {
            try {
                node.next(resource)
            } catch (thrown: Throwable) {
                if (woken.get()) null else throw thrown
            }
        }
    }

    /** Whether the next [next] returns without blocking; never once the run was woken to stop or closed. */
    fun ready(): Boolean = !woken.get() && !closed.get() && node.ready(resource)

    /** Tells a blocked [next] to return; a resource already closed is not asked. */
    fun wake() {
        if (woken.compareAndSet(false, true) && !closed.get()) node.wake(resource)
    }

    /**
     * Closed once, after the last read. A read still running on another thread, a feed's fork given up by a restart, is
     * woken first: a Kafka consumer closed under its own `poll` throws and stays in its group.
     *
     * A read that has not returned [readEndsWithin] after its wake has a `wake` that does not reach it. It is closed
     * under all the same, with a warning, since a close that waited for it would never come back.
     */
    fun close() {
        val held =
            reading.tryLock() ||
                run {
                    wake()
                    waitForRead()
                }
        if (!held) {
            logWarn(
                "a blocking source at ${node.at} was closed while still reading: its wake did not end the read " +
                    "within $readEndsWithin, or the close was interrupted",
            )
        }
        try {
            if (closed.compareAndSet(false, true)) node.close(resource)
        } finally {
            if (held) reading.unlock()
        }
    }

    // A close interrupted while it waits closes at once and keeps the interrupt: leaving the resource open is worse.
    private fun waitForRead(): Boolean =
        try {
            reading.tryLock(readEndsWithin.inWholeMilliseconds, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
}

/** How long a close waits for a woken read to return, before it closes under it. */
private val READ_ENDS_WITHIN: Duration = 5.seconds
