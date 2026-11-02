package io.github.matthewjones372.surefold.stream

import dev.surefold.VirtualThreads
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException

/** How the stream a feeding fork read ended: marked in its queue after the last thing it started. */
internal sealed interface Fed {
    data object Done : Fed

    class Threw(val thrown: Throwable) : Fed
}

/**
 * How often a pull yields before it parks on a body or stage that is not done yet. The head of a wide window is
 * usually moments from done, and a few yields cost far less than the park and the unpark from whichever thread
 * completes it.
 */
internal const val YIELDS_BEFORE_WAITING = 4

/** An element and the stage the caller started for it. */
private class Started(val element: Any, val stage: CompletableFuture<Any?>)

/**
 * Up to [window] of the caller's stages at once, answered in the order the elements came. Upstream is on
 * a fork of its own, which starts each stage as its element arrives, so a stage that is done is passed on
 * even while upstream waits for the next element: a source that blocks, a Kafka consumer on a quiet topic
 * among them, waits for as long as it is quiet. A stage that fails is the defect `mapAsync` names, and one
 * that completes with `null` is too, as on Pekko. The run interrupts the fork and waits for it when it ends.
 *
 * The started stages wait in a [Ring] of [window]: the head leaves it only once it has answered, so the ring's
 * capacity is the window, and the fork is woken for room a batch at a time rather than once per answer.
 */
context(ctx: PullContext)
internal fun Node.MapAsync.fed(window: Int): Pull {
    val start = guarded("mapAsync", at, f)
    val up = upstream.pull()
    val started = Ring(window)
    val fork = InFlight(ctx.releases?.on ?: VirtualThreads) {
        feed(up, started) { a ->
            @Suppress("UNCHECKED_CAST")
            Started(a, (start(a) as CompletionStage<Any?>).toCompletableFuture())
        }
    }
    ctx.releases?.add(fork::cancel)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = started.awaitHead()) {
            Fed.Done -> null.also { ended = true }
            is Fed.Threw -> throw next.thrown.also { ended = true }
            else -> answered(next as Started).also { started.poll() }
        }
    }
}

/**
 * Upstream's elements, each started by [start] once the ring has room for it, then how upstream ended. The
 * room is waited for before upstream is pulled, so nothing is started that the window has no place for, and
 * `offer` cannot be cut short by an interrupt, so nothing started is lost before the run can let go of it.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, after what came first.
@Suppress("TooGenericExceptionCaught")
internal fun feed(up: Pull, started: Ring, start: (Any) -> Any) {
    try {
        while (true) {
            started.awaitRoom()
            started.offer(start(up.next() ?: break))
        }
        started.offer(Fed.Done)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    } catch (thrown: Throwable) {
        try {
            started.awaitRoom()
            started.offer(Fed.Threw(thrown))
        } catch (_: InterruptedException) {
            // The run let go while this waited for room: nobody reads the failure either.
        }
    }
}

/**
 * As [fed], but each answer is passed on as soon as its stage completes (spec 0170): a stage's completion puts it
 * in [Arrivals] for the pull, from whichever thread completed it, so nothing waits on the oldest.
 */
context(ctx: PullContext)
internal fun Node.MapAsync.fedUnordered(): Pull {
    val start = guarded("mapAsync", at, f)
    val up = upstream.pull()
    val arrivals = Arrivals(parallelism)
    val fork = InFlight(ctx.releases?.on ?: VirtualThreads) {
        feedUnordered(up, arrivals) { a ->
            @Suppress("UNCHECKED_CAST")
            val stage = (start(a) as CompletionStage<Any?>).toCompletableFuture()
            stage.whenComplete { _, _ -> arrivals.arrive(Started(a, stage)) }
        }
    }
    ctx.releases?.add(fork::cancel)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = arrivals.take()) {
            Fed.Done -> null.also { ended = true }
            is Fed.Threw -> throw next.thrown.also { ended = true }
            else -> answered(next as Started).also { arrivals.released() }
        }
    }
}

/** The stage's value, waited on, or the defect it failed with. */
private fun Node.MapAsync.answered(next: Started): Any {
    var yielded = 0
    while (!next.stage.isDone && yielded++ < YIELDS_BEFORE_WAITING) Thread.yield()
    val b = try {
        next.stage.get()
    } catch (failed: ExecutionException) {
        throw (failed.cause ?: failed).unwrapped().describedBy("mapAsync", next.element, at)
    }
    return b ?: throw NullPointerException("${facts("mapAsync", next.element, at)}: the stage completed with null")
}
