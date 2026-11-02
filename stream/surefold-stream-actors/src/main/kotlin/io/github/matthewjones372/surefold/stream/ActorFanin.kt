package io.github.matthewjones372.surefold.stream

import dev.surefold.fail.nonFatalOrThrow
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Behaviour
import io.github.matthewjones372.surefold.actor.Ctx
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.stay
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How many elements the inputs of a merge may hold between them before each waits for the reader, as on Forks. */
internal const val MERGED = 16

/** What an input actor tells itself: that it may pull again. */
internal data object Room

/** What an input puts after its last element, or in place of the rest when it failed. */
private sealed interface Mark {
    /** An input has ended. */
    data object Finished : Mark

    /** An inner stream of a `flatMapMerge` has started, so the reader waits for its end as well. */
    data object Started : Mark

    /** The outer stream of a `flatMapMerge` has ended: no inner stream starts after this. */
    data object OuterDone : Mark

    class Threw(val thrown: Throwable) : Mark
}

/**
 * One queue that inputs put into and one reader takes from, with room for [room] elements between them. An input that
 * finds no room stalls, and the reader wakes one stalled input for each element it takes. Of an input and the reader,
 * whichever looks last wakes it: an input stalls before it looks for room again, and the reader makes room before it
 * looks for a stall.
 */
internal class Inflow(room: Int, private var open: Int, private var outerDone: Boolean) {
    private val queue = LinkedBlockingQueue<Any>()
    private val room = AtomicInteger(room)
    private val stalled = ConcurrentLinkedQueue<ActorRef<Room>>()

    fun hasRoom(): Boolean = room.get() > 0

    fun put(element: Any) {
        room.decrementAndGet()
        queue.put(element)
    }

    fun finished() = queue.put(Mark.Finished)

    fun started() = queue.put(Mark.Started)

    fun outerDone() = queue.put(Mark.OuterDone)

    fun threw(thrown: Throwable) = queue.put(Mark.Threw(thrown))

    fun stall(input: ActorRef<Room>) {
        stalled.add(input)
        if (room.get() > 0 && stalled.remove(input)) input.tell(Room)
    }

    /** The next element in the order the inputs put them, or null once every input has ended. */
    fun next(): Any? {
        while (open > 0 || !outerDone) {
            when (val taken = queue.take()) {
                Mark.Finished -> open--
                Mark.Started -> open++
                Mark.OuterDone -> outerDone = true
                is Mark.Threw -> throw taken.thrown
                else -> return taken.also { took() }
            }
        }
        return null
    }

    private fun took() {
        room.incrementAndGet()
        stalled.poll()?.tell(Room)
    }
}

/**
 * Pulls [up] into [into] while there is room, [batch] at a time so that it shares its runner, and stalls when the
 * room runs out. [ended] runs once it has put its end.
 */
internal class Input(
    private val up: Pull,
    private val into: Inflow,
    private val batch: Int,
    private val ended: () -> Unit = {},
) {
    private var done = false

    fun behaviour(): Behaviour<Room, Unit, Nothing> =
        behaviour<Room, Unit>(Unit) { ctx, _, _ ->
            feed(ctx.self)
            stay()
        }.onStart { ctx -> ctx.self.tell(Room) }

    // Whatever upstream threw ends what the reader sees, after every element before it.
    @Suppress("TooGenericExceptionCaught")
    private fun feed(self: ActorRef<Room>) {
        var pulled = 0
        while (!done && into.hasRoom() && pulled < batch) {
            val a = try {
                up.next()
            } catch (thrown: Throwable) {
                end { into.threw(thrown.nonFatalOrThrow()) }
                return
            }
            if (a == null) {
                end(into::finished)
            } else {
                into.put(a)
                pulled++
            }
        }
        when {
            done -> Unit
            into.hasRoom() -> self.tell(Room)
            else -> into.stall(self)
        }
    }

    private fun end(put: () -> Unit) {
        done = true
        put()
        ended()
    }
}

/**
 * The outer stream of a `flatMapMerge`: each element [inner] builds a stream from, which starts as an [Input] of this
 * actor's while fewer than [breadth] run. An inner input that ends tells this actor it has room again.
 */
internal class Outer(
    private val up: Pull,
    private val inner: (Any) -> Pull,
    private val breadth: Int,
    private val into: Inflow,
    private val batch: Int,
) {
    private val running = AtomicInteger()
    private var done = false
    private var started = 0

    fun behaviour(): Behaviour<Room, Unit, Nothing> =
        behaviour<Room, Unit>(Unit) { ctx, _, _ ->
            startInners(ctx)
            stay()
        }.onStart { ctx -> ctx.self.tell(Room) }

    // Whatever the outer stream or a build threw is the reader's, after every element before it.
    @Suppress("TooGenericExceptionCaught")
    private fun startInners(ctx: Ctx<Room>) {
        while (!done && running.get() < breadth) {
            try {
                val a = up.next()
                if (a == null) {
                    done = true
                    into.outerDone()
                } else {
                    val pull = inner(a)
                    running.incrementAndGet()
                    into.started()
                    val input = Input(pull, into, batch) {
                        running.decrementAndGet()
                        ctx.self.tell(Room)
                    }
                    ctx.spawn("inner-${++started}", input.behaviour(), null)
                }
            } catch (thrown: Throwable) {
                done = true
                into.threw(thrown.nonFatalOrThrow())
            }
        }
    }
}

/**
 * What upstream has piled up since the reader last took, and how upstream ended. Upstream never waits on the reader:
 * while the reader is slow, each element is folded into what is pending.
 */
internal class Heap(private val seed: (Any) -> Any, private val aggregate: (Any, Any) -> Any) {
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

    /** What is pending, once there is any; null once upstream has ended with nothing left. */
    fun take(): Any? = lock.withLock {
        // A failure ends the stream at once, as Pekko's does: what was pending is not emitted first.
        while (pending == null && !ended) changed.await()
        threw?.let { throw it }
        pending.also { pending = null }
    }
}

/** `conflate`'s upstream on an actor: folds [batch] elements into [pile] a step, and goes on until upstream ends. */
internal class Folding(
    private val up: Pull,
    private val pile: Heap,
    private val batch: Int,
) {
    fun behaviour(): Behaviour<Room, Unit, Nothing> =
        behaviour<Room, Unit>(Unit) { ctx, _, _ ->
            if (fold()) ctx.self.tell(Room)
            stay()
        }.onStart { ctx -> ctx.self.tell(Room) }

    /** Whether upstream goes on. */
    // Whatever upstream, the seed or the aggregate threw is the reader's.
    @Suppress("TooGenericExceptionCaught")
    private fun fold(): Boolean =
        try {
            var more = true
            var folded = 0
            while (more && folded < batch) {
                val a = up.next()
                if (a == null) {
                    pile.end()
                    more = false
                } else {
                    pile.add(a)
                    folded++
                }
            }
            more
        } catch (thrown: Throwable) {
            pile.end(thrown.nonFatalOrThrow())
            false
        }
}
