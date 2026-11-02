package dev.surefold.stream

import dev.surefold.VirtualThreads

/** How the stream a buffer holds for ended: marked in the queue after its last element. */
private sealed interface Ended {
    data object Done : Ended

    class Threw(val thrown: Throwable) : Ended
}

/**
 * Upstream on a fork of its own, into a [Ring] of [Node.Buffer.size]: it runs ahead until the ring is
 * full, and the pull takes from the ring. What upstream ended with arrives after its last element. The
 * run interrupts the fork and waits for it when it ends, however it ends.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, in order.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.Buffer.buffered(): Pull {
    val up = upstream.pull()
    val queue = Ring(size)
    val fork = InFlight(ctx.releases?.on ?: VirtualThreads) {
        try {
            up.into(queue)
        } catch (_: InterruptedException) {
            // Let go of by the run: nothing reads what would have come next.
        } catch (thrown: Throwable) {
            // Waits for room like an element: the pull takes what came before it first, and the run
            // interrupts this if nobody will.
            try {
                queue.put(Ended.Threw(thrown))
            } catch (_: InterruptedException) {
                // The run let go while this waited: nobody reads the failure either.
            }
        }
    }
    ctx.releases?.add(fork::cancel)
    var ended = false
    return Pull {
        if (ended) {
            null
        } else {
            val next = queue.take()
            when {
                next === Ended.Done -> null.also { ended = true }
                next is Ended.Threw -> throw next.thrown.also { ended = true }
                else -> next
            }
        }
    }
}

/** Every element of this pull into [queue], then [Ended.Done]: a loop, as a sequence would wrap each element. */
private fun Pull.into(queue: Ring) {
    while (true) queue.put(next() ?: break)
    queue.put(Ended.Done)
}

/**
 * The same on a test's clock: upstream is a worker of its own, which fills the buffer in its turns and
 * parks while it is full, and the pull parks while it is empty.
 */
// The catch is as wide as a pipeline, as above.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.Buffer.bufferedOnClock(turns: Turns): Pull {
    val up = upstream.pull()
    val held = ArrayDeque<Any>()
    turns.fork {
        try {
            while (!turns.stopped) {
                val a = up.next()
                held.addLast(a ?: Ended.Done)
                if (a == null) break
                turns.park { held.size < size }
            }
        } catch (thrown: Throwable) {
            held.addLast(Ended.Threw(thrown))
        }
    }
    return Pull {
        while (held.isEmpty() && !turns.stopped) turns.park { held.isNotEmpty() }
        when (val next = held.firstOrNull()) {
            null, Ended.Done -> null
            is Ended.Threw -> throw next.thrown
            else -> held.removeFirst()
        }
    }
}
