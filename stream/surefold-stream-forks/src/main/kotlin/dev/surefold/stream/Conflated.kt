package dev.surefold.stream

import dev.surefold.VirtualThreads
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * What upstream has piled up since the reader last took, and how upstream ended. Upstream never waits on the reader:
 * while the reader is slow, each element is folded into what is pending.
 */
private class Pile(private val seed: (Any) -> Any, private val aggregate: (Any, Any) -> Any) {

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var pending: Any? = null
    private var ended = false
    private var threw: Throwable? = null

    fun add(a: Any) = lock.withLock {
        pending = pending?.let { aggregate(it, a) } ?: seed(a)
        changed.signalAll()
    }

    fun end(failure: Throwable? = null) = lock.withLock {
        ended = true
        threw = failure
        changed.signalAll()
    }

    /** What is pending, once there is any; `null` once upstream has ended with nothing left. */
    fun take(): Any? = lock.withLock {
        // A failure ends the stream at once, as Pekko's does: what was pending is not emitted first.
        while (pending == null && !ended) changed.await()
        threw?.let { throw it }
        pending.also { pending = null }
    }
}

/**
 * Upstream on a fork of its own, folding into one pending value while the reader is slow, as Pekko's `conflateWithSeed`
 * does. The fork checks for an interruption between elements, so a run can let go of it even while upstream never
 * blocks.
 */
// The catch is as wide as a pipeline: whatever upstream, the seed or the aggregate threw is the reader's.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.Conflate.conflated(): Pull {
    val pile = Pile(guarded("conflateWithSeed", at, seed), guarded("conflateWithSeed", at, aggregate))
    val up = upstream.pull()
    val fork =
        InFlight(ctx.releases?.on ?: VirtualThreads) {
            try {
                while (!Thread.currentThread().isInterrupted) pile.add(up.next() ?: break)
                pile.end()
            } catch (_: InterruptedException) {
                // Let go of by the run.
            } catch (thrown: Throwable) {
                pile.end(thrown)
            }
        }
    ctx.releases?.add(fork::cancel)
    return Pull { pile.take() }
}

/**
 * [Node.Conflate] on a test's clock: upstream is a worker that folds everything it has in the same instant before the
 * reader's turn, so a source with no time in it arrives as one aggregate.
 */
context(ctx: PullContext)
internal fun Node.Conflate.conflatedOnClock(turns: Turns): Pull =
    TurnPile(guarded("conflateWithSeed", at, seed), guarded("conflateWithSeed", at, aggregate), turns).also { pile ->
        turns.fork { pile.fill(upstream.pull()) }
    }

/** The pile on a test's clock, where only the worker whose turn it is touches it, so it needs no lock. */
private class TurnPile(
    private val seed: (Any) -> Any,
    private val aggregate: (Any, Any) -> Any,
    private val turns: Turns,
) : Pull {
    private var pending: Any? = null
    private var ended = false
    private var threw: Throwable? = null

    // The catch is as wide as a pipeline: whatever upstream, the seed or the aggregate threw is the reader's.
    @Suppress("TooGenericExceptionCaught")
    fun fill(up: Pull) {
        try {
            generateSequence { if (turns.stopped) null else up.next() }
                .forEach { a -> pending = pending?.let { aggregate(it, a) } ?: seed(a) }
        } catch (thrown: Throwable) {
            threw = thrown
        }
        ended = true
    }

    override fun next(): Any? {
        while (pending == null && !ended && !turns.stopped) turns.park { pending != null || ended }
        threw?.let { throw it }
        return pending.also { pending = null }
    }
}
