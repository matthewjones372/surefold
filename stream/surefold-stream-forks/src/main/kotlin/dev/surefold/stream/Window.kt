package dev.surefold.stream

import dev.surefold.Clock
import dev.surefold.VirtualThreads
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fold
import java.lang.invoke.MethodHandles
import java.lang.invoke.VarHandle
import java.util.concurrent.BlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.LockSupport

/**
 * What a run has to let go of when it ends, however it ends: the bodies still in flight and the forks still filling a
 * buffer. Released in reverse, on the thread that ran the loop, before the exit completes.
 *
 * [on] is where the run starts every thread it starts for itself, so that whoever gave the backend its executor sees
 * them all, and [clock] is the time its pulls with time in them read.
 */
internal class Releases(val on: Executor, val clock: Clock) {

    /** Where a body given [asked] runs: the run's executor, unless the caller named one of its own. */
    fun executorFor(asked: Executor): Executor = if (asked === VirtualThreads) on else asked

    private val each = ArrayDeque<() -> Unit>()

    fun add(release: () -> Unit) = synchronized(each) { each.addFirst(release) }

    /**
     * Lets go of everything, each on its own: one that throws does not stop the rest, and what it threw rides on the
     * first. A stop's interrupt is spent by then, so no wait here is cut short by it.
     */
    // The catch is as wide as a release: whatever one threw, the others still run.
    @Suppress("TooGenericExceptionCaught")
    fun releaseAll() {
        Thread.interrupted()
        val failures =
            synchronized(each) { each.toList().also { each.clear() } }
                .mapNotNull { release ->
                    try {
                        release()
                        null
                    } catch (thrown: Throwable) {
                        thrown
                    }
                }
        failures.firstOrNull()?.let { first ->
            failures.drop(1).forEach(first::addSuppressed)
            throw first
        }
    }
}

/**
 * One body on its own thread: its answer, and a way to interrupt it and wait until it has stopped.
 *
 * One object rather than a future, a latch and a lock around them (spec 0178), since a window makes one for every
 * element. Whatever waits for the body (the pull in [await], the run in [cancel], an action left by [whenDone]) is
 * pushed onto [state], and the body, once stopped, swaps in [STOPPED] and wakes or runs everything it took. A waiter
 * pushes itself and then parks only while [state] is not [STOPPED], and the push and the swap are atomic on the one
 * field, so either the body's swap takes the waiter or the waiter sees [STOPPED]: no wake can fall between.
 */
// The catch is as wide as a body: whatever it threw is the answer the pull loop rethrows.
@Suppress("TooGenericExceptionCaught")
internal class InFlight<T>(on: Executor, private val body: () -> T) : Runnable {

    // null, the newest of a chain of [Waiting], or [STOPPED] once the answer below is set. Written only through
    // [STATE], which detekt cannot see.
    @Suppress("VarCouldBeVal") @Volatile private var state: Any? = null
    private var answer: T? = null
    private var thrown: Throwable? = null

    // Guarded by this: the thread to interrupt is the one running the body, and only while it is.
    private var runner: Thread? = null
    private var cancelled = false

    init {
        on.execute(this)
    }

    /** The body's, on the thread [on] gave it: not for anyone else to call. */
    override fun run() {
        val going = synchronized(this) { (!cancelled).also { if (it) runner = Thread.currentThread() } }
        try {
            if (going) answer = body() else thrown = CancellationException("cancelled before it started")
        } catch (failed: Throwable) {
            thrown = failed
        } finally {
            synchronized(this) { runner = null }
            stopped()
        }
    }

    // Every thread first, then every action: a pull parked here should not wait for an action to finish.
    private fun stopped() {
        val taken = STATE.getAndSet(this, STOPPED)
        generateSequence(taken as? Waiting) { it.next as? Waiting }.forEach { it.thread?.let(LockSupport::unpark) }
        generateSequence(taken as? Waiting) { it.next as? Waiting }.forEach { it.action?.invoke() }
    }

    /** Runs [action] once the body has answered, thrown or been cancelled, on whichever thread got there. */
    fun whenDone(action: () -> Unit) {
        if (!push(Waiting(null, action))) action()
    }

    /** The body's answer, or what it threw, as it threw it. Interruptible, so that a stop wakes the loop. */
    fun await(): T {
        // A body at the head of a wide window is usually moments from done; see [YIELDS_BEFORE_WAITING].
        var yielded = 0
        while (state !== STOPPED && yielded++ < YIELDS_BEFORE_WAITING) Thread.yield()
        if (state !== STOPPED) waitUntilStopped(interruptible = true)
        thrown?.let { throw it }
        @Suppress("UNCHECKED_CAST")
        return answer as T
    }

    /**
     * Interrupts the body if it is running, stops it running if it has not started, and waits for it to have stopped,
     * whatever interrupts the wait: a fork is let go of only once it is gone.
     */
    fun cancel() {
        synchronized(this) {
            cancelled = true
            runner?.interrupt()
        }
        if (state !== STOPPED) waitUntilStopped(interruptible = false)
    }

    // A waiter that gives up on an interrupt stays in the chain, and the body's swap unparks it once more later.
    // Every park in this module waits in a loop on its own condition, so that wake is spurious and harmless.
    private fun waitUntilStopped(interruptible: Boolean) {
        if (!push(Waiting(Thread.currentThread(), null))) return
        var interrupted = false
        while (state !== STOPPED) {
            LockSupport.park(this)
            if (Thread.interrupted()) {
                if (interruptible) throw InterruptedException()
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    /** Adds [waiting] to the chain the body wakes, or says the body has already stopped. */
    private fun push(waiting: Waiting): Boolean {
        while (true) {
            val now = state
            if (now === STOPPED) return false
            waiting.next = now
            if (STATE.compareAndSet(this, now, waiting)) return true
        }
    }

    /** A thread parked on the body, or an action for once it has stopped, and the one pushed before it. */
    private class Waiting(val thread: Thread?, val action: (() -> Unit)?) {
        var next: Any? = null
    }

    private companion object {
        val STOPPED = Any()
        val STATE: VarHandle = MethodHandles.lookup().findVarHandle(InFlight::class.java, "state", Any::class.java)
    }
}

/**
 * Up to [Node.MapPar.parallelism] bodies at once, each on the node's executor (the run's, where the node was left with
 * the default), answered in the order the elements came. Upstream is on a fork of its own, which starts each body as
 * its element arrives, so a body that is done is passed on even while upstream waits for the next element: a source
 * that blocks, a Kafka consumer on a quiet topic among them, waits for as long as it is quiet. The fork and the bodies
 * are let go of when the run ends, and a failure the window answers with interrupts the bodies behind it.
 */
// The catch is as wide as a body's, and rethrows what it caught once the window behind it is let go of.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.MapPar.window(): Pull {
    val body = guarded("mapPar", at) { a: Any -> attempt { f(a) } }
    val up = upstream.pull()
    val executor = ctx.releases?.executorFor(on) ?: on
    val started = Ring(parallelism)
    val fork =
        InFlight(ctx.releases?.on ?: VirtualThreads) {
            feed(up, started) { a -> InFlight(executor) { body(a) } }
        }
    // The fork first, so nothing is started behind the bodies being let go of.
    val cancelAll = {
        fork.cancel()
        generateSequence { started.poll() }.filterIsInstance<InFlight<*>>().forEach { it.cancel() }
    }
    ctx.releases?.add(cancelAll)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = started.awaitHead()) {
            Fed.Done -> null.also { ended = true }

            is Fed.Threw -> throw next.thrown.also { ended = true }

            else -> {
                @Suppress("UNCHECKED_CAST") val head = next as InFlight<Either<Any?, Any>>
                val answer =
                    try {
                        head.await()
                    } catch (thrown: Throwable) {
                        // The head is still in the ring, so it is let go of with the rest.
                        cancelAll()
                        throw thrown
                    }
                started.poll()
                answer.fold(
                    { e ->
                        cancelAll()
                        throw DeclaredFailure(e)
                    },
                    { b -> b },
                )
            }
        }
    }
}

/**
 * As [window], but each body is passed on as soon as it is done (spec 0170): a body puts itself in [Arrivals] for the
 * pull once it ends, so a slow body holds back only itself. [running] is what a failure or the run's end lets go of.
 */
// The catch is as wide as a body's, and rethrows what it caught once the window behind it is let go of.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.MapPar.unorderedWindow(): Pull {
    val body = guarded("mapPar", at) { a: Any -> attempt { f(a) } }
    val up = upstream.pull()
    val executor = ctx.releases?.executorFor(on) ?: on
    val arrivals = Arrivals(parallelism)
    val running = ConcurrentHashMap.newKeySet<InFlight<*>>()
    val fork =
        InFlight(ctx.releases?.on ?: VirtualThreads) {
            feedUnordered(up, arrivals) { a ->
                val flight = InFlight(executor) { body(a) }
                running.add(flight)
                flight.whenDone { arrivals.arrive(flight) }
            }
        }
    // The fork first, so nothing is started behind the bodies being let go of.
    val cancelAll = {
        fork.cancel()
        running.forEach { it.cancel() }
    }
    ctx.releases?.add(cancelAll)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = arrivals.take()) {
            Fed.Done -> null.also { ended = true }

            is Fed.Threw -> throw next.thrown.also { ended = true }

            else -> {
                @Suppress("UNCHECKED_CAST") val done = next as InFlight<Either<Any?, Any>>
                running.remove(done)
                val answer =
                    try {
                        done.await()
                    } catch (thrown: Throwable) {
                        cancelAll()
                        throw thrown
                    }
                arrivals.released()
                answer.fold(
                    { e ->
                        cancelAll()
                        throw DeclaredFailure(e)
                    },
                    { b -> b },
                )
            }
        }
    }
}

/**
 * As [window], but each body is passed on as soon as it is done and every earlier element with its key has been:
 * [released] queues the bodies in the order the pull takes them. A key's tail leaves [tails] once it is released while
 * still the newest for its key, so the map holds only keys with a body not yet passed on.
 */
// The catch is as wide as a body's, and rethrows what it caught once the window behind it is let go of.
@Suppress("TooGenericExceptionCaught")
context(ctx: PullContext)
internal fun Node.MapPar.keyedWindow(key: (Any) -> Any): Pull {
    val body = guarded("mapPar", at) { a: Any -> attempt { f(a) } }
    val keyOf = guarded("mapParKeyed", at, key)
    val up = upstream.pull()
    val executor = ctx.releases?.executorFor(on) ?: on
    val room = Semaphore(parallelism)
    val released = LinkedBlockingQueue<Any>()
    val running = ConcurrentHashMap.newKeySet<InFlight<*>>()
    val tails = ConcurrentHashMap<Any, CompletableFuture<Unit>>()
    val fork =
        InFlight(ctx.releases?.on ?: VirtualThreads) {
            keyedFeed(up, room, released, tails) { a ->
                val k = keyOf(a)
                val behind = tails[k] ?: CompletableFuture.completedFuture(Unit)
                // Started before it is the key's tail: a body the executor refuses leaves no tail nothing completes.
                val flight = InFlight(executor) { body(a) }
                running.add(flight)
                val passed = CompletableFuture<Unit>()
                tails[k] = passed
                flight.whenDone {
                    behind.whenComplete { _, _ ->
                        running.remove(flight)
                        released.offer(flight)
                        passed.complete(Unit)
                        tails.remove(k, passed)
                    }
                }
            }
        }
    // The fork first, so nothing is started behind the bodies being let go of.
    val cancelAll = {
        fork.cancel()
        running.forEach { it.cancel() }
    }
    ctx.releases?.add(cancelAll)
    var ended = false
    return Pull {
        if (ended) return@Pull null
        when (val next = released.take()) {
            Fed.Done -> null.also { ended = true }

            is Fed.Threw -> throw next.thrown.also { ended = true }

            else -> {
                @Suppress("UNCHECKED_CAST")
                val answer =
                    try {
                        (next as InFlight<Either<Any?, Any>>).await()
                    } catch (thrown: Throwable) {
                        cancelAll()
                        throw thrown
                    }
                room.release()
                answer.fold(
                    { e ->
                        cancelAll()
                        throw DeclaredFailure(e)
                    },
                    { b -> b },
                )
            }
        }
    }
}

/**
 * Upstream's elements, each started by [start] once there is [room] for it, then how upstream ended, marked only once
 * every key's last body has been released, so that nothing started is passed on after it.
 */
// The catch is as wide as a pipeline: whatever upstream threw is the pull's to throw, after what came first.
@Suppress("TooGenericExceptionCaught")
private fun keyedFeed(
    up: Pull,
    room: Semaphore,
    released: BlockingQueue<Any>,
    tails: Map<Any, CompletableFuture<Unit>>,
    start: (Any) -> Unit,
) {
    try {
        val ended =
            try {
                while (true) {
                    room.acquire()
                    start(up.next() ?: break)
                }
                Fed.Done
            } catch (stopped: InterruptedException) {
                throw stopped
            } catch (thrown: Throwable) {
                Fed.Threw(thrown)
            }
        tails.values.forEach { it.get() }
        released.offer(ended)
    } catch (_: InterruptedException) {
        // Let go of by the run: nothing reads what would have come next.
    }
}
