package dev.surefold.actor

import java.lang.Thread.State.BLOCKED
import java.lang.Thread.State.TIMED_WAITING
import java.lang.Thread.State.WAITING
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** Something a runner can run: an actor's activation. */
internal fun interface Activation {
    fun activate()
}

/** How long a runner with nothing to take spins before it parks: a wake that close behind needs no unpark. */
private val SPIN_NANOS = TimeUnit.MICROSECONDS.toNanos(10)

/** How long a parked runner waits to be woken before it leaves: a mailbox that empties often starts no thread. */
private val KEEP_NANOS = TimeUnit.SECONDS.toNanos(1)

private const val RUNNING = 0
private const val PARKED = 1
private const val RETIRED = 2

/** How often the watcher looks at the runners while work is queued. */
private val WATCH_NANOS = TimeUnit.MICROSECONDS.toNanos(50)

/**
 * The virtual threads that run one scope's actors. A woken actor joins a queue, and [parallelism] runners, one per
 * carrier, take actors from it in turn, so a burst of wakes starts a handful of threads rather than one each. A
 * runner that finds nothing spins briefly, then parks until a submit wakes it or a second passes, so an actor whose
 * mailbox empties between messages starts no thread per message (spec 0143). A turn that blocks parks
 * its runner; when every runner is in a turn and the queue stands still, a watcher adds runners, doubling each time
 * it still stands still, up to [cap].
 */
internal class Runners(private val parallelism: Int, private val cap: Int) {
    /** One runner, and whether it has an activation in hand; only the runner itself writes it. */
    private class Runner {
        lateinit var thread: Thread

        @Volatile
        var busy = false

        /** [RUNNING], or [PARKED] and wakeable, or [RETIRED]; a waker claims a parked runner by moving it on. */
        val state = AtomicInteger(RUNNING)

        /** On the parked stack, so that each runner is on it at most once. */
        @Volatile
        var listed = false

        /**
         * How long this runner spins before it parks, which only it reads or writes (spec 0144): halved after each
         * park that outlasted a full spin, and reset to [SPIN_NANOS] by a wake a full spin would have caught.
         */
        var spin = SPIN_NANOS

        /** In a turn that is parked on something: a lock, a sleep, a read. */
        fun blocked(): Boolean = busy && thread.state.let { it == WAITING || it == TIMED_WAITING || it == BLOCKED }
    }

    /** A node of the parked stack, fresh for each push so a popped node cannot come back under a waker's CAS. */
    private class Parked(val runner: Runner, val next: Parked?)

    private val ready = ConcurrentLinkedQueue<Activation>()

    // Activations taken before any in [ready]: the few actors every node's health depends on, such as the cluster's
    // own, which must not wait behind thousands of busy entities for their turn (spec 0104).
    private val urgent = ConcurrentLinkedQueue<Activation>()

    private fun nothingQueued() = urgent.isEmpty() && ready.isEmpty()
    private val active = AtomicInteger()

    /** How many runner threads this has started, for the test that says a busy actor does not start one each. */
    internal val starts = AtomicInteger()

    /**
     * Nanoseconds runners have spun in all, for the test that says a wide gap is not spun through (spec 0144). A
     * `LongAdder`, since every runner that runs dry adds to it (spec 0293).
     */
    internal val spun = LongAdder()

    /** How many dry spells runners have spun through, for the test that says a runner at zero spin does not. */
    internal val spins = LongAdder()
    private val watching = AtomicBoolean()
    private val started = ConcurrentHashMap.newKeySet<Runner>()
    private val spinning = AtomicInteger()

    // Most recently parked first, so the warmest runner is woken and the coldest left to retire.
    private val parked = AtomicReference<Parked?>()

    // A runner serves whichever actors are queued, so it is named by its number, not an actor's (spec 0308).
    private val runnerThreads = Thread.ofVirtual().name("surefold-actor-runner-", 0).factory()
    private val lock = ReentrantLock()
    private val quiet = lock.newCondition()

    // How many threads wait in awaitIdle, read by a runner each time it finds the queue empty.
    @Volatile
    private var awaiting = 0

    /**
     * Waits until nothing is queued and no runner holds an activation. The queue is read before the runners: an
     * activation not yet taken is still queued, and one taken belongs to a runner that marked itself busy first.
     */
    fun awaitIdle() = lock.withLock {
        awaiting++
        try {
            while (!nothingQueued() || started.any { it.busy }) quiet.await()
        } finally {
            awaiting--
        }
    }

    fun submit(activation: Activation, first: Boolean = false) {
        if (first) urgent.offer(activation) else ready.offer(activation)
        // A spinning runner sees the queue after this offer, or decrements before its last look and sees it then.
        if (spinning.get() == 0) signal()
    }

    /** Starts a runner when fewer than [limit] run; whether it did. */
    private fun grow(limit: Int): Boolean {
        while (true) {
            val running = active.get()
            if (running >= limit) return false
            if (active.compareAndSet(running, running + 1)) {
                starts.incrementAndGet()
                val runner = Runner()
                runner.thread = runnerThreads.newThread { run(runner) }
                started.add(runner)
                runner.thread.start()
                return true
            }
        }
    }

    private tailrec fun run(runner: Runner) {
        runner.busy = true
        val next = urgent.poll() ?: ready.poll()
        if (next != null) {
            // A submit that saw this runner spinning left the rest of a burst to it: pass it on.
            if (spinning.get() == 0 && !nothingQueued()) signal()
            runOne(next)
            return run(runner)
        }
        runner.busy = false
        if (awaiting > 0) lock.withLock { quiet.signalAll() }
        val dry = System.nanoTime()
        if (spunInto(runner, dry)) return run(runner)
        if (parks(runner)) {
            // Woken within a full spin of running dry, a full spin would have caught it; later, spinning was wasted.
            runner.spin = if (System.nanoTime() - dry <= SPIN_NANOS) SPIN_NANOS else runner.spin / 2
            return run(runner)
        }
        started.remove(runner)
        active.decrementAndGet()
        // An actor queued after the last poll, by a wake that counted this runner as still running, is ours to take.
        if (nothingQueued() || !rejoin()) return
        started.add(runner)
        run(runner)
    }

    /** Wakes a parked runner, else starts one, else leaves it to the watcher. */
    private fun signal() {
        if (!wake() && !grow(parallelism)) watch()
    }

    /** Hands queued work to a parked runner: whether there was one. */
    private fun wake(): Boolean {
        while (true) {
            val runner = pop() ?: return false
            runner.listed = false
            if (runner.state.compareAndSet(PARKED, RUNNING)) {
                LockSupport.unpark(runner.thread)
                return true
            }
        }
    }

    private fun push(runner: Runner) {
        while (true) {
            val head = parked.get()
            if (parked.compareAndSet(head, Parked(runner, head))) return
        }
    }

    private fun pop(): Runner? {
        while (true) {
            val head = parked.get() ?: return null
            if (parked.compareAndSet(head, head.next)) return head.runner
        }
    }

    /** Parks for up to [KEEP_NANOS] waiting to be woken: whether it was, or false to retire. */
    private fun parks(runner: Runner): Boolean {
        runner.state.set(PARKED)
        // Left on the stack by a runner that claimed itself or retired, an entry stays: a waker skips it.
        if (!runner.listed) {
            runner.listed = true
            push(runner)
        }
        // Work queued before this runner was on the stack found no one to wake, so it is this runner's.
        if (!nothingQueued() && runner.state.compareAndSet(PARKED, RUNNING)) return true
        val deadline = System.nanoTime() + KEEP_NANOS
        while (runner.state.get() == PARKED) {
            val left = deadline - System.nanoTime()
            if (left <= 0 && runner.state.compareAndSet(PARKED, RETIRED)) return false
            LockSupport.parkNanos(left)
        }
        return true
    }

    /**
     * Whether work arrived while [runner] spun, counted among the spinning. A runner whose spin has halved to nothing
     * goes straight to parking and touches no shared counter; [dry] still tells a near miss, which gives it a full
     * spin again (spec 0293).
     */
    private fun spunInto(runner: Runner, dry: Long): Boolean {
        if (runner.spin == 0L) return false
        spinning.incrementAndGet()
        try {
            return workArrives(runner, dry)
        } finally {
            spinning.decrementAndGet()
        }
    }

    /** Spins for up to [runner]'s spin from [dry], watching for work without taking it: whether some arrived. */
    private fun workArrives(runner: Runner, dry: Long): Boolean {
        val deadline = dry + runner.spin
        var now = dry
        spins.increment()
        try {
            while (now < deadline) {
                if (!nothingQueued()) return true
                Thread.onSpinWait()
                now = System.nanoTime()
            }
            return false
        } finally {
            spun.add(now - dry)
        }
    }

    private fun rejoin(): Boolean {
        while (true) {
            val running = active.get()
            if (running >= cap) return false
            if (active.compareAndSet(running, running + 1)) return true
        }
    }

    /** Starts the watcher, if it is not already watching. */
    private fun watch() {
        if (!watching.get() && watching.compareAndSet(false, true)) {
            Thread.ofVirtual().name("surefold-actor-watcher").start(::watcher)
        }
    }

    /**
     * While work is queued, keeps [parallelism] runners able to run: those parked in a blocking turn do not count,
     * and the shortfall is made up with new runners, doubling each tick it lasts, so a burst of blocking actors is
     * each on a runner of its own within a few ticks. Leaves once the queue is empty.
     */
    private fun watcher() {
        var adding = 0
        while (true) {
            LockSupport.parkNanos(WATCH_NANOS)
            if (nothingQueued()) {
                watching.set(false)
                // Work queued after the check, by a wake that saw the watcher still watching, is still ours.
                if (nothingQueued() || !watching.compareAndSet(false, true)) return
            }
            val runnable = active.get() - started.count { it.blocked() }
            if (runnable < parallelism) {
                adding = maxOf(parallelism - runnable, adding * 2).coerceAtMost(cap)
                repeat(adding) { grow(cap) }
            } else {
                adding = 0
            }
        }
    }

    // An actor's throw ends that actor and goes to the thread's handler, as it did on a thread of its own; the
    // runner carries on with the next.
    @Suppress("TooGenericExceptionCaught")
    private fun runOne(activation: Activation) {
        try {
            activation.activate()
        } catch (fatal: VirtualMachineError) {
            active.decrementAndGet()
            throw fatal
        } catch (thrown: Throwable) {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler.uncaughtException(thread, thrown)
        }
    }
}
