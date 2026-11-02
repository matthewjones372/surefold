package io.github.matthewjones372.surefold

import io.github.matthewjones372.surefold.fail.Fail
import io.github.matthewjones372.surefold.fail.attempt
import io.github.matthewjones372.surefold.fail.fold
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Whether `async` forks where it is written, or waits until something asks for the value. */
enum class Start { Eager, Lazy }

/** A [Fail] scope that owns every thread forked in it: no fork outlives the block that opened it. */
interface Scope<E> : Fail<E> {
    /** The executor this scope's forks run on when a call does not name another. */
    val on: Executor

    /**
     * Forks [block] onto [on] under a nested scope of its own. A [Start.Lazy] fork waits for `await`, and
     * one nobody awaits never runs at all, so its raise is never the scope's `Left`.
     */
    fun <T> async(on: Executor = this.on, start: Start = Start.Eager, block: Scope<E>.() -> T): Deferred<T>

    /**
     * Runs [hook] on the closing thread when this scope starts to close, before any fork is interrupted, so the
     * scope's forks are still running for it: to leave a cluster, or to wait for work in flight to land. Hooks run
     * in reverse order of registration, as a `resourceScope` releases. A hook that throws is logged and the scope
     * closes all the same; one interrupted waiting ends, the rest still run, and the interrupt is kept for after.
     */
    fun onClose(hook: () -> Unit)
}

/**
 * Opens a scope nothing in [block] can raise in, on the calling thread, and closes it when [block] leaves — by
 * return or throw — interrupting every fork still running and joining all of them before this returns. It answers
 * [block]'s value: a scope with an error to raise is opened inside `attempt { }`, by the [Fail] form below.
 */
fun <A> scope(on: Executor = VirtualThreads, block: Scope<Nothing>.() -> A): A = Unraisable.scope(on, block)

/**
 * The same scope inside a [Fail] already in hand, raising into it, so `attempt { scope { async { } } }` answers an
 * `Either`; a fork still opens a boundary of its own, because a scope never crosses a thread.
 */
fun <E, A> Fail<E>.scope(on: Executor = VirtualThreads, block: Scope<E>.() -> A): A {
    val nest = Nest(this, on)
    val value = try {
        nest.block()
    } finally {
        nest.close()
    }
    // Only reached on a return, so a fork nobody awaited still gets to fail its parent.
    nest.unnoticedFailure()?.let { nest.surface(it) }
    return value
}

/**
 * A fork's body opens its own boundary and its outcome is held until `await`; a throw has to be caught
 * here, or it would end the fork's thread with nobody left to answer for it.
 */
private fun <E, T> capture(on: Executor, block: Scope<E>.() -> T): Outcome<E, T> =
    try {
        attempt { this.scope(on, block) }.fold({ Raised(it) }, { Returned(it) })
    } catch (t: Throwable) {
        Thrown(t)
    }

/**
 * The owner the top-level combinators fork under: `Nothing` has no value, so nothing running inside one
 * can hand it an error to raise.
 */
internal object Unraisable : Fail<Nothing> {
    override fun fail(error: Nothing): Nothing = error
}

/**
 * The owner an accumulating combinator forks under: it keeps its branches' errors and answers with all of
 * them at once, so no fork ever hands one back to be surfaced on its own.
 */
internal object Uncollected : Fail<Any?> {
    override fun fail(error: Any?): Nothing = error("an accumulated error is answered by its combinator")
}

private fun <E> Fail<E>.surface(failure: Failure<E>): Nothing = when (failure) {
    is Raised -> fail(failure.error)
    is Thrown -> throw failure.throwable
}

/** What a fork interrupted by a combinator answers with, as against a failure the caller's work ran into. */

/**
 * An interrupt landing on the closing thread must not leave a fork running, so the join is retried
 * and the flag handed back to the caller afterwards.
 */
private fun Thread.joinFully() {
    var interrupted = false
    while (isAlive) {
        try { join() } catch (stop: InterruptedException) { interrupted = true }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

private class Nest<E>(private val owner: Fail<E>, override val on: Executor) : Scope<E> {

    override fun fail(error: E): Nothing = owner.fail(error)

    // Only the scope's own thread reaches this: a fork body is handed a nest of its own.
    private val forks = mutableListOf<Fork<E, *>>()

    // Registered from any thread that holds the scope, such as an actor started in it.
    private val hooks = CopyOnWriteArrayList<() -> Unit>()

    override fun onClose(hook: () -> Unit) {
        hooks += hook
    }

    override fun <T> async(on: Executor, start: Start, block: Scope<E>.() -> T): Deferred<T> {
        val fork = Fork(this, on, block, start = start)
        forks += fork
        return fork
    }

    /**
     * Interrupt is the only cancellation the JDK has, so every fork gets one before any of them is joined;
     * a fork whose turn on the executor has not come yet starts its body interrupted instead.
     */
    fun close() {
        // A hook that throws an Error still leaves no fork running: it is thrown once every one has ended.
        try {
            runHooks()
        } finally {
            forks.forEach { it.interrupt() }
            forks.forEach { it.join() }
        }
    }

    fun unnoticedFailure(): Failure<E>? = forks.firstNotNullOfOrNull { it.unnoticedFailure() }

    private fun runHooks() {
        var interrupted = false
        // Last registered first, and one registered while closing runs too: it is the last now.
        generateSequence { hooks.removeLastOrNull() }.forEach { hook ->
            try {
                hook()
            } catch (stop: InterruptedException) {
                interrupted = true
            } catch (thrown: Exception) {
                logError("a close hook failed; the scope closes all the same", thrown)
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }
}

/**
 * The forks a combinator owns rather than the scope: it opens them, waits on the calling thread, and does
 * not return until every one has ended, so none of them is left for the scope to notice at close.
 */
internal class Flight<E>(private val owner: Fail<E>, private val on: Executor) {

    private val forks = mutableListOf<Fork<E, *>>()

    // The thread settling the forks, woken as each one ends; set before it first looks, so no end goes unseen.
    @Volatile
    private var settler: Thread? = null

    fun <T> fork(block: Scope<E>.() -> T): Fork<E, T> =
        Fork(owner, on, block, { Waiting.unpark(settler) }).also { forks += it }

    /** Waits for every fork, or for the first of them to fail, then ends the rest and surfaces that failure. */
    fun settleAll() {
        settle { forks.none { fork -> fork.hasFailed() } }
        surfaceFirstFailure()
    }

    /** Waits for the first fork to answer, whatever it answers, then ends the rest and surfaces a failure. */
    fun settleFirst() {
        settle { forks.none { fork -> fork.hasEnded() } }
        surfaceFirstFailure()
    }

    /**
     * Waits for every fork to answer, or for the first of them to throw; a raise does not end a sibling here,
     * so the errors come back in start order for the caller to answer with together.
     */
    fun settleEvery(): List<E> {
        settle { forks.none { fork -> fork.hasThrown() } }
        forks.firstNotNullOfOrNull { it.ownThrow() }?.let { owner.surface(it) }
        return forks.mapNotNull { it.raisedError() }
    }

    /** Ends every fork started so far, for a combinator stopped before it could settle them. */
    fun abandon() {
        forks.forEach { it.interrupt() }
        forks.forEach { it.join() }
    }

    private fun surfaceFirstFailure() {
        forks.firstNotNullOfOrNull { it.ownFailure() }?.let { owner.surface(it) }
    }

    private fun settle(waiting: () -> Boolean) {
        try {
            // Once the answer is in, the forks still running are interrupted below rather than waited out.
            settler = Thread.currentThread()
            while (waiting() && forks.any { !it.hasEnded() }) {
                Waiting.park(this)
                if (Thread.interrupted()) throw InterruptedException("interrupted while waiting on its forks")
            }
        } finally {
            forks.forEach { it.interrupt() }
            forks.forEach { it.join() }
        }
    }
}

/**
 * One body run once on [on], as one explicit state (spec 0037): every move between states is a compare-and-set on
 * [state], and the threads waiting for it to end ride in the state, parked, until the move to `Done` wakes them.
 *
 * ```
 * Unstarted ─start─▶ Submitted ─claim─▶ Running ─body ends─▶ Done
 *     │                  │                 │
 *   cancel             cancel            cancel
 *     ▼                  ▼                 ▼
 *   Done           Submitted(cancelled)  Interrupting ─sent─▶ Interrupted ─body ends─▶ Done
 *                        │
 *                      claim: the body starts interrupted ─▶ Interrupted
 * ```
 */
internal class Fork<E, T>(
    private val owner: Fail<E>,
    private val on: Executor,
    private val block: Scope<E>.() -> T,
    private val ended: () -> Unit = {},
    start: Start = Start.Eager,
) : Deferred<T> {

    private val state = AtomicReference<ForkState<E, T>>(ForkState.Unstarted(null))

    @Volatile
    private var noticed = false

    // An interrupt sent to a fork that had not answered yet: what comes back is that interrupt rather than
    // anything the caller asked for, so a combinator drops it.
    @Volatile
    private var cutShort = false

    // Close interrupts a fork `cancel` already stopped, and by then it has answered: only the first interrupt says
    // whether the fork was cut short.
    private val cancelled = AtomicBoolean()

    // A fork on the default executor has a thread of its own, which `join` waits out as well, so a scope
    // that has returned has left nothing alive. A borrowed thread outlives the fork and cannot be joined.
    @Volatile
    private var ownThread: Thread? = null

    // Read on the opening thread rather than inside the task, which runs on a thread that bound none of it.
    private val inherited = Bindings.snapshot()

    init {
        if (start == Start.Eager) submit()
    }

    /** Moves an unstarted fork to the executor's queue: the executor is handed a ticket, and the body runs once. */
    private fun submit() {
        while (true) {
            val now = state.get() as? ForkState.Unstarted ?: return
            if (state.compareAndSet(now, ForkState.Submitted(now.waiters, cancelled = false))) break
        }
        // Told before the executor has it, so a test taking turns knows of this fork before anything else can happen.
        val ticket = Waiting.forking()
        on.execute {
            Bindings.under(inherited) {
                Waiting.started(ticket)
                try {
                    run()
                } finally {
                    Waiting.ended(ticket)
                }
            }
        }
    }

    /** The executor's turn: runs the body, unless an `await` claimed it first or a cancel ended it in the queue. */
    private fun run() {
        if (!claim(Thread.currentThread(), own = on === VirtualThreads)) return
        val outer = runningFor.get()
        runningFor.set(on)
        try {
            body()
        } finally {
            runningFor.set(outer)
        }
    }

    /**
     * Moves a [ForkState.Submitted] fork to [thread], which then runs the body: whether it did. Cancelled before its
     * turn came, the body starts interrupted rather than running on as though its scope were still open. Only a
     * thread the fork has to itself is [own], for `join` to wait out.
     */
    private fun claim(thread: Thread, own: Boolean): Boolean {
        while (true) {
            val now = state.get() as? ForkState.Submitted ?: return false
            if (own) ownThread = thread
            val claimed = if (now.cancelled) {
                ForkState.Interrupted(thread, now.waiters)
            } else {
                ForkState.Running(thread, now.waiters)
            }
            if (state.compareAndSet(now, claimed)) {
                if (now.cancelled) thread.interrupt()
                return true
            }
        }
    }

    /** The body, on the thread that claimed it, and the fork's end: whether an interrupt had come, now cleared. */
    private fun body(): Boolean = finish(capture(on, block))

    /**
     * The body's own thread, and only it, moves a running fork to `Done`. Not while an interrupt is on its way: that
     * interrupt would land on whatever the executor runs next. An interrupt that came is cleared once it is `Done`,
     * and whether one had is answered.
     */
    private fun finish(outcome: Outcome<E, T>): Boolean {
        while (true) {
            val now = state.get()
            if (now is ForkState.Interrupting) {
                Thread.onSpinWait()
                continue
            }
            if (state.compareAndSet(now, ForkState.Done(outcome, started = true))) {
                val interrupted = Thread.interrupted()
                wake(now.waiters)
                ended()
                return interrupted
            }
        }
    }

    override fun await(): T {
        startOnce()
        help()
        join()
        noticed = true
        return when (val settled = settled()) {
            is Returned -> settled.value
            is Failure -> owner.surface(settled)
        }
    }

    /**
     * A fork still waiting its turn on the executor runs here, on the thread that awaits it, when that thread is
     * itself one of the executor's (spec 0037): a bounded pool whose every thread awaits a fork still in its queue
     * would otherwise wait for ever. The fork still runs on its executor, as it was asked to, and its ticket, when
     * the executor comes to it, finds the fork claimed and does nothing. Virtual threads are never short, and give
     * each fork a thread of its own, so a fork on them is never helped. A thread already interrupted waits instead,
     * and an interrupt meant for this thread rather than for the fork is handed back once the body has ended.
     */
    private fun help() {
        val me = Thread.currentThread()
        if (on === VirtualThreads || runningFor.get() !== on || me.isInterrupted) return
        if (!claim(me, own = false)) return
        val interrupted = Bindings.under(inherited) { body() }
        if (interrupted && !cancelled.get()) me.interrupt()
    }

    private fun startOnce() = submit()

    override fun cancel() {
        interrupt()
        join()
        noticed = true
    }

    fun interrupt() {
        // Only the first interrupt says whether the fork was cut short: by a later one it may have answered.
        val first = cancelled.compareAndSet(false, true)
        while (true) {
            val now = state.get()
            when (now) {
                is ForkState.Done, is ForkState.Interrupting, is ForkState.Interrupted -> return

                // Nothing is running to notice an interrupt, and nothing will end the fork, so it answers here as
                // though it had been stopped at its first interruptible call.
                is ForkState.Unstarted -> if (state.compareAndSet(now, ForkState.Done(beforeStart(), false))) {
                    if (first) cutShort = true
                    wake(now.waiters)
                    return
                }

                // Its turn on the executor has not come: its claim starts the body interrupted.
                is ForkState.Submitted -> if (now.cancelled || state.compareAndSet(now, now.cancelledFirst())) {
                    if (first) cutShort = true
                    return
                }

                is ForkState.Running -> if (state.compareAndSet(now, ForkState.Interrupting(now.thread, now.waiters))) {
                    if (first) cutShort = true
                    Waiting.interrupt(now.thread)
                    sent()
                    return
                }
            }
        }
    }

    /** The interrupt [interrupt] sent has landed: the body may now end, carrying whoever waits on it on. */
    private fun sent() {
        while (true) {
            val now = state.get() as ForkState.Interrupting
            if (state.compareAndSet(now, ForkState.Interrupted(now.thread, now.waiters))) return
        }
    }

    /**
     * Waits for the fork to end, however often the waiting thread is interrupted meanwhile: an interrupt on the
     * closing thread must not leave a fork running, so it is handed back to the caller afterwards.
     */
    fun join() {
        if (waitsOn()) parkUntilDone()
        ownThread?.joinFully()
    }

    /** Whether this thread is now among the fork's waiters: false once it has ended, or if it never started. */
    private fun waitsOn(): Boolean {
        val me = Thread.currentThread()
        while (true) {
            val now = state.get()
            if (now is ForkState.Unstarted || now is ForkState.Done) return false
            if (state.compareAndSet(now, now.waitedBy(Waiter(me, now.waiters)))) return true
        }
    }

    /** Starts the fork if it waits to be, and has it wake this thread when it ends: false if it already has. */
    fun wakesMe(): Boolean {
        startOnce()
        return waitsOn()
    }

    /** This thread, waiting by [wakesMe], no longer waits: a `select` another clause won leaves nothing behind. */
    fun forgetMe() {
        val me = Thread.currentThread()
        while (true) {
            val now = state.get()
            if (generateSequence(now.waiters) { it.next }.none { it.thread === me }) return
            if (state.compareAndSet(now, now.waitedBy(without(now.waiters, me)))) return
        }
    }

    /** How many threads wait for the fork to end, for a test that says a select left none behind. */
    fun waiting(): Int = generateSequence(state.get().waiters) { it.next }.count()

    private fun without(waiters: Waiter?, thread: Thread): Waiter? = when {
        waiters == null -> null
        waiters.thread === thread -> waiters.next
        else -> Waiter(waiters.thread, without(waiters.next, thread))
    }

    private fun parkUntilDone() {
        var interrupted = false
        while (state.get() !is ForkState.Done) {
            Waiting.park(this)
            if (Thread.interrupted()) interrupted = true
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    fun hasFailed(): Boolean = outcome() is Failure

    fun hasEnded(): Boolean = outcome() != null

    fun hasThrown(): Boolean = outcome() is Thrown

    /** Whether this fork answered of its own accord, rather than being cut short by a combinator's interrupt. */
    fun answered(): Boolean = !cutShort

    /**
     * The failure this fork answered with of its own accord. One cut short by a combinator's interrupt had not answered
     * when it was sent, so whatever it throws after is the interrupt's doing: a pool that wraps it in an exception of
     * its own would otherwise be reported in place of the failure that ended its siblings. An interrupt arrives as a
     * throw and never as a raise, so a raise is the fork's own however late it lands (spec 0121).
     */
    fun ownFailure(): Failure<E>? = (settled() as? Failure<E>)?.takeUnless { cutShort && it is Thrown }

    /** The throwable this fork threw of its own accord: a throw ends an accumulating combinator's siblings. */
    fun ownThrow(): Thrown? = ownFailure() as? Thrown

    /** The error this fork raised of its own accord, for the combinator that accumulates rather than surfaces. */
    fun raisedError(): E? = when (val failure = ownFailure()) {
        is Raised -> failure.error
        is Thrown -> null
        null -> null
    }

    /** The failure of a fork whose outcome nobody asked for; one that never ran has none to answer with. */
    fun unnoticedFailure(): Failure<E>? = when (val now = state.get()) {
        is ForkState.Unstarted -> null
        is ForkState.Done -> if (now.started) now.outcome.takeUnless { noticed } as? Failure<E> else null
        else -> settled().takeUnless { noticed } as? Failure<E>
    }

    private fun outcome(): Outcome<E, T>? = (state.get() as? ForkState.Done)?.outcome

    private fun settled(): Outcome<E, T> = checkNotNull(outcome()) { "a joined fork always has an outcome" }

    private fun wake(waiters: Waiter?) =
        generateSequence(waiters) { it.next }.forEach { Waiting.unpark(it.thread) }

    private fun beforeStart() = Thrown(InterruptedException("cancelled before it was started"))
}

/** The executor whose fork body this thread is running, if any: where an `await` on it may help (spec 0037). */
private val runningFor = ThreadLocal<Executor?>()

/** A thread parked until a fork is [ForkState.Done], and the one parked before it. */
private data class Waiter(val thread: Thread, val next: Waiter?)

/** Where a fork is (spec 0037); every state but [ForkState.Done] carries the threads waiting for it to end. */
private sealed interface ForkState<out E, out T> {
    val waiters: Waiter?

    /** This state, with [waiters] waiting on it instead. */
    fun waitedBy(waiters: Waiter?): ForkState<E, T>

    class Unstarted(override val waiters: Waiter?) : ForkState<Nothing, Nothing> {
        override fun waitedBy(waiters: Waiter?) = Unstarted(waiters)
    }

    /** Queued on the executor; [cancelled] once a cancel came before its turn, so its body starts interrupted. */
    class Submitted(override val waiters: Waiter?, val cancelled: Boolean) : ForkState<Nothing, Nothing> {
        override fun waitedBy(waiters: Waiter?) = Submitted(waiters, cancelled)

        fun cancelledFirst() = Submitted(waiters, cancelled = true)
    }

    class Running(val thread: Thread, override val waiters: Waiter?) : ForkState<Nothing, Nothing> {
        override fun waitedBy(waiters: Waiter?) = Running(thread, waiters)
    }

    /** A cancel is sending [thread] its interrupt; the body cannot end until it has. */
    class Interrupting(val thread: Thread, override val waiters: Waiter?) : ForkState<Nothing, Nothing> {
        override fun waitedBy(waiters: Waiter?) = Interrupting(thread, waiters)
    }

    class Interrupted(val thread: Thread, override val waiters: Waiter?) : ForkState<Nothing, Nothing> {
        override fun waitedBy(waiters: Waiter?) = Interrupted(thread, waiters)
    }

    /** Ended, with [outcome]; [started] is false for a lazy fork cancelled before anyone asked for it. */
    class Done<E, T>(val outcome: Outcome<E, T>, val started: Boolean) : ForkState<E, T> {
        override val waiters: Waiter? get() = null

        override fun waitedBy(waiters: Waiter?) = this
    }
}

internal sealed interface Outcome<out E, out T>

internal class Returned<T>(val value: T) : Outcome<Nothing, T>

internal sealed interface Failure<out E> : Outcome<E, Nothing>

internal class Raised<E>(val error: E) : Failure<E>

internal class Thrown(val throwable: Throwable) : Failure<Nothing>
