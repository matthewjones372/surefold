package dev.surefold.stream

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Schedule
import java.lang.invoke.MethodHandles
import java.time.Instant
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration

// The pulls with time in them, on a test's clock or on Forks' own. Each waits by parking on the run's
// [Timeline]: on a test's clock the clock stops at the instant it waits for, and on Forks it waits for it.

private fun Instant.plus(duration: Duration): Instant = plusNanos(duration.inWholeNanoseconds)

// The file's own class, as `logWarn` would name it from here (spec 0127): a top-level initializer runs in it.
private val TIMED: String = MethodHandles.lookup().lookupClass().name

/**
 * The first element [Node.Tick.after] from the first pull, then one every [Node.Tick.every]. A tick that
 * falls due while nothing is asking is dropped, as Pekko's is: the next pull waits for the next one.
 */
context(ctx: PullContext)
internal fun Node.Tick.tick(turns: Timeline): Pull {
    var due: Instant? = null
    return Pull {
        val now = turns.now()
        val next = generateSequence(due ?: now.plus(after)) { it.plus(every) }.first { it >= now }
        turns.park(until = next)
        if (turns.stopped) {
            null
        } else {
            due = next.plus(every)
            element
        }
    }
}

/**
 * What one fork hands another: an element, or how the stream it pulls from ended, taken once. Taking is
 * one atomic step, since on Forks the two are on threads of their own: a read and a separate clear
 * would erase an element put down between them.
 */
private class Handoff {
    sealed interface Ended {
        data object Done : Ended

        class Threw(val thrown: Throwable) : Ended
    }

    private val slot = AtomicReference<Any?>(null)

    /** What is held. Only the feed puts something down, and only once the slot is empty. */
    var held: Any?
        get() = slot.get()
        set(value) = slot.set(value)

    fun take(): Any? = slot.getAndSet(null)
}

/** [up] pulled on a worker of its own, one element at a time into [into], taken before the next is pulled. */
// The catch is as wide as a pipeline: whatever upstream threw is the consumer's to throw, on its own worker.
@Suppress("TooGenericExceptionCaught")
private fun Timeline.feed(up: Pull, into: Handoff) = fork {
    try {
        while (!stopped) {
            val next = up.next()
            into.held = next ?: Handoff.Ended.Done
            wake()
            if (next == null) break
            park { into.held == null }
        }
    } catch (thrown: Throwable) {
        into.held = Handoff.Ended.Threw(thrown)
        wake()
    }
}

/**
 * Upstream is pulled by a worker of its own, so that a window can close while an element is still to come.
 * Windows follow each other every [Node.GroupedWithin.within] from the first pull, and a full group starts
 * the next window from the instant it was emitted, as Pekko's do. A window that closes empty emits nothing.
 */
context(ctx: PullContext)
internal fun Node.GroupedWithin.groupedWithin(turns: Timeline): Pull =
    if (turns is RealTime) {
        Queued(n, within, turns, ArrayBlockingQueue<Any>(n + 1).also { turns.feed(upstream.pull(), it) })
    } else {
        Windows(n, within, turns, Handoff().also { turns.feed(upstream.pull(), it) })
    }

/**
 * [up] pulled on a fork of its own into [into], which has room for a group and one end: the feed runs at most a
 * group ahead of the reader, and parks only when it is that far ahead.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the reader's to throw, after what came before it.
@Suppress("TooGenericExceptionCaught")
private fun RealTime.feed(up: Pull, into: ArrayBlockingQueue<Any>) = fork {
    try {
        generateSequence { up.next() }.forEach(into::put)
        into.put(Handoff.Ended.Done)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    } catch (thrown: Throwable) {
        try {
            into.put(Handoff.Ended.Threw(thrown))
        } catch (_: InterruptedException) {
            // Let go of by the run while waiting for room: nobody reads the failure either.
        }
    }
}

/**
 * [Windows] on real time: the reader drains whatever the feed has queued in one go, and reads the clock and parks
 * only when the queue is empty, until the window closes. The windows follow the same rules.
 */
private class Queued(val n: Int, val within: Duration, val time: RealTime, val queue: ArrayBlockingQueue<Any>) : Pull {

    private var closes: Instant? = null
    private var ended = false
    private val drained = ArrayList<Any>(n + 1)

    override fun next(): Any? {
        val group = ArrayList<Any>(n)
        var emit: List<Any>? = null
        while (emit == null && !ended) emit = fill(group)
        return (emit ?: group).takeIf { it.isNotEmpty() }
    }

    /** One step of filling [group]: the group to emit, if this step ends it. */
    private fun fill(group: MutableList<Any>): List<Any>? {
        if (closes == null) closes = time.now().plus(within)
        drained.clear()
        queue.drainTo(drained, n - group.size)
        if (drained.isEmpty()) queue.poll(untilClose(), TimeUnit.NANOSECONDS)?.let(drained::add)
        drained.forEach { held ->
            when (held) {
                Handoff.Ended.Done -> return group.also { ended = true }
                is Handoff.Ended.Threw -> throw held.thrown
                else -> group.add(held)
            }
        }
        return when {
            group.size == n -> group.also { closes = time.now().plus(within) }
            drained.isEmpty() -> closed(group)
            else -> null
        }
    }

    private fun untilClose(): Long = java.time.Duration.between(time.now(), closes).toNanos().coerceAtLeast(0)

    /** Nothing came before the window's close: once it has passed, the next window starts and what it held goes. */
    private fun closed(group: List<Any>): List<Any>? {
        val now = time.now()
        val window = checkNotNull(closes)
        if (window > now) return null
        closes = generateSequence(window) { it.plus(within) }.first { it > now }
        return group.takeIf { it.isNotEmpty() }
    }
}

private class Windows(val n: Int, val within: Duration, val turns: Timeline, val handoff: Handoff) : Pull {

    private var closes: Instant? = null
    private var ended = false

    override fun next(): Any? {
        val group = ArrayList<Any>(n)
        var emit: List<Any>? = null
        while (emit == null && !ended && !turns.stopped) emit = fill(group)
        return (emit ?: group).takeIf { it.isNotEmpty() }
    }

    /** One step of filling [group]: the group to emit, if this step ends it. */
    private fun fill(group: MutableList<Any>): List<Any>? {
        val now = turns.now()
        val window = closes ?: now.plus(within).also { closes = it }
        val held = handoff.take()
        // Taken, so the feed may pull the next.
        if (held != null) turns.wake()
        return when (held) {
            Handoff.Ended.Done -> group.also { ended = true }
            is Handoff.Ended.Threw -> throw held.thrown
            null -> closeOrWait(group, window, now)
            else -> group.apply { add(held) }.takeIf { it.size == n }?.also { closes = now.plus(within) }
        }
    }

    private fun closeOrWait(group: List<Any>, window: Instant, now: Instant): List<Any>? =
        if (window <= now) {
            closes = generateSequence(window) { it.plus(within) }.first { it > now }
            group.takeIf { it.isNotEmpty() }
        } else {
            turns.park(until = window) { handoff.held != null }
            null
        }
}

/**
 * Upstream again, from its first element, after each defect the schedule continues on, and after the delay
 * it asks for on the run's clock. A declared failure is not a defect, and passes through as it is; nor is
 * an interruption, which is a stop reaching the run.
 */
// The catch is as wide as a pipeline, because a defect is whatever upstream threw that it did not declare.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.RestartOnDefect.restarting(turns: Timeline): Pull {
    // Each attempt opens its blocking sources in a scope of its own, so a restart closes what the attempt it
    // gives up held (a consumer's place in its group, say) before the next attempt opens its own.
    val run = ctx.resources
    var attempt = run?.attempt()
    fun attempted(): Pull =
        attempt?.let { scope -> with(ctx.within(scope)) { upstream.pull() } } ?: upstream.pull()
    var current = attempted()
    var schedule = step
    return Pull {
        var element: Any? = null
        var over = false
        while (!over) {
            try {
                element = current.next()
                over = true
            } catch (failure: DeclaredFailure) {
                throw failure
            } catch (defect: Throwable) {
                // An interruption is a stop reaching the run, never a defect to restart on: restarting
                // would spend the interrupt, and the run would wait on its next tick for ever.
                if (defect.isInterruption()) throw defect
                when (val decision = schedule(defect)) {
                    is Schedule.Decision.Continue -> {
                        logger.log(
                            LogLine(
                                LogLevel.Warn,
                                "surefold-stream: restarting in ${decision.delay} after $defect",
                                turns.now(),
                                defect,
                                origin = TIMED,
                            ),
                        )
                        schedule = decision.step
                        attempt?.let { run?.givenUp(it) }?.let(defect::addSuppressed)
                        turns.park(until = turns.now().plus(decision.delay))
                        attempt = run?.attempt()
                        current = attempted()
                    }

                    is Schedule.Decision.Done -> throw defect
                }
            }
        }
        element
    }
}
