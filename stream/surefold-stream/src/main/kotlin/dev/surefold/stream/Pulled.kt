package dev.surefold.stream

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One run of a stream, handed to a reader through [buffer] slots. Every wait and every change to what is waiting
 * happens under [lock], so a [close] cannot slip between a put's check of [closed] and its wait for room.
 */
internal class Pulled<A : Any>(stream: Stream<Nothing, A>, backend: StreamBackend, private val buffer: Int) :
    Iterator<A>,
    AutoCloseable {

    private val lock = ReentrantLock()
    private val arrived = lock.newCondition()
    private val taken = lock.newCondition()
    private val waiting = ArrayDeque<A>(buffer)
    private val closed = AtomicBoolean(false)

    private val ended = AtomicReference<Ended?>(null)

    private val running: Running<Nothing, Unit> = stream.runFold(Unit) { _, element -> hand(element) }.start(backend)

    init {
        // The end needs no room in the buffer, so the thread completing the stage, a Pekko dispatcher's, never waits.
        running.exit.whenComplete { exit, failure -> end(Ended(failure ?: exit.cause())) }
    }

    /** A wait the run's own stop interrupts throws, and the backend takes that as the stop it was. */
    private fun hand(element: A) = lock.withLock {
        while (waiting.size == buffer && !closed.get()) taken.await()
        if (!closed.get()) {
            waiting.addLast(element)
            arrived.signal()
        }
    }

    private fun end(how: Ended) = lock.withLock {
        ended.set(how)
        arrived.signalAll()
    }

    override fun hasNext(): Boolean = lock.withLock {
        while (waiting.isEmpty() && ended.get() == null && !closed.get()) arrived.await()
        when {
            closed.get() -> false
            waiting.isNotEmpty() -> true
            else -> ended.get()?.cause?.let { throw it } ?: false
        }
    }

    override fun next(): A = lock.withLock {
        if (!hasNext()) throw NoSuchElementException("the run has ended")
        waiting.removeFirst().also { taken.signal() }
    }

    /** Releases a put waiting for room before stopping the run, since a run cannot stop past a blocked stage. */
    override fun close() {
        lock.withLock {
            closed.set(true)
            waiting.clear()
            taken.signalAll()
            arrived.signalAll()
        }
        running.close()
    }

    private fun Exit<Nothing, Unit>.cause(): Throwable? = when (this) {
        is Exit.Done -> null
        is Exit.Failed -> error
        is Exit.Died -> cause
    }

    /** A run that ended, with the cause it died of, if it died. */
    private class Ended(val cause: Throwable?)
}
