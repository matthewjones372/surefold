package dev.surefold.replay

import dev.surefold.TurnSeam
import dev.surefold.Turns
import java.util.SplittableRandom
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** How many orderings in a row a planted change of priority can reach: PCT's depth. */
private const val DEPTH = 3

/** The steps a run is guessed to take, over which the changes of priority are spread. */
private const val STEPS = 64

/** How long the thread holding the turn may wait outside surefold before the run is said to have left determinism. */
private const val STUCK_NANOS = 1_000_000_000L

/** How often the watch looks at the thread holding the turn. */
private const val WATCH_MILLIS = 50L

/** What a thread waiting outside surefold, or blocked on a monitor, reports itself as. */
private val waitingOutside = setOf(Thread.State.WAITING, Thread.State.TIMED_WAITING, Thread.State.BLOCKED)

/**
 * One order of a run's forks, decided by [seed] (spec 0042). One thread holds the turn at a time; at each place it
 * waits through surefold it hands the turn back, and the runnable fork of highest priority goes next. Priorities are
 * drawn as forks are opened, and at [DEPTH] less one seeded steps the thread holding the turn drops below every other:
 * probabilistic concurrency testing, which finds an ordering bug of depth [DEPTH] with a probability it can bound.
 *
 * Every draw and every choice is made while one thread holds the turn, so a seed decides the whole order of any run
 * whose forks wait only through surefold.
 */
@OptIn(TurnSeam::class)
internal class Order(seed: Long) : Turns {

    /** A fork, or the thread that started the run, in the order the run opened them. */
    private class Slot(var priority: Double) {
        var thread: Thread? = null
        var blocked = false
        var permit = false
    }

    private val lock = ReentrantLock()
    private val turned = lock.newCondition()
    private val random = SplittableRandom(seed)
    private val slots = mutableListOf<Slot>()
    private val byThread = HashMap<Thread, Slot>()
    private var holder: Slot? = null
    private var steps = 0
    private var lowest = 0.0
    private val changes = List(DEPTH - 1) { 1 + random.nextInt(STEPS) }.toSet()
    private var passedAt = System.nanoTime()
    private var watching = true

    // Once a thread holds the turn waiting outside surefold, no order can be promised: every thread runs freely so
    // the run can end, and what that thread was doing is kept for the failure.
    private var free = false

    /** Where the thread holding the turn waited outside surefold, if it did: the run then left determinism. */
    var stuck: String? = null
        private set

    /** The calling thread starts the run, holding the turn, with a watch on whoever holds it. */
    fun enter() {
        lock.withLock {
            val slot = Slot(random.nextDouble()).also { it.thread = Thread.currentThread() }
            slots += slot
            byThread[Thread.currentThread()] = slot
            holder = slot
            passedAt = System.nanoTime()
        }
        Thread.ofPlatform().daemon().name("surefold-replay-watch").start(::watch)
    }

    /** The calling thread has finished the run: anything it left still running takes the turn, and the watch ends. */
    fun leave() {
        ended()
        lock.withLock {
            watching = false
            turned.signalAll()
        }
    }

    /**
     * Ends determinism when the thread holding the turn has waited outside surefold, in `Thread.sleep`, a monitor or
     * a blocking call, for longer than [STUCK_NANOS]: nothing surefold sees would ever hand the turn on.
     */
    private fun watch() = lock.withLock {
        while (watching) {
            turned.await(WATCH_MILLIS, TimeUnit.MILLISECONDS)
            val held = holder?.thread ?: continue
            if (!free && held.state in waitingOutside && System.nanoTime() - passedAt > STUCK_NANOS) {
                stuck = "${held.name.ifEmpty { "a fork" }} held the turn waiting outside surefold, at:\n" +
                    held.stackTrace.joinToString("\n") { "\tat $it" }
                free = true
                turned.signalAll()
            }
        }
    }

    override fun forking(): Any = lock.withLock { Slot(random.nextDouble()).also { slots += it } }

    override fun started(ticket: Any) = lock.withLock {
        val slot = ticket as Slot
        slot.thread = Thread.currentThread()
        byThread[Thread.currentThread()] = slot
        awaitTurn(slot)
    }

    override fun ended() = lock.withLock {
        val slot = byThread.remove(Thread.currentThread()) ?: return@withLock
        slots -= slot
        pass(slot)
    }

    override fun parking(): Boolean = lock.withLock {
        val slot = byThread[Thread.currentThread()] ?: return@withLock true
        // A wake that came first is this park's: the turn still goes round, and this thread does not park.
        val woken = slot.permit
        if (woken) slot.permit = false else slot.blocked = true
        pass(slot)
        if (woken) awaitTurn(slot)
        !woken
    }

    override fun woke() = lock.withLock {
        val slot = byThread[Thread.currentThread()] ?: return@withLock
        awaitTurn(slot)
    }

    override fun waking(thread: Thread) = lock.withLock {
        val slot = byThread[thread] ?: return@withLock
        if (slot.blocked) slot.blocked = false else slot.permit = true
    }

    /** The turn leaves [from] for the runnable slot of highest priority, which may be [from] again. */
    private fun pass(from: Slot) {
        passedAt = System.nanoTime()
        steps++
        if (steps in changes) from.priority = --lowest
        holder = slots.filterNot { it.blocked }.maxByOrNull { it.priority }
        turned.signalAll()
    }

    /** Waits for [slot] to hold the turn; an interrupt meanwhile is kept for the fork, which may be its answer. */
    private fun awaitTurn(slot: Slot) {
        while (!free && holder !== slot) turned.awaitUninterruptibly()
    }
}
