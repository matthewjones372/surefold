package io.github.matthewjones372.surefold.actor

import io.github.matthewjones372.surefold.Carriers
import io.github.matthewjones372.surefold.Clock
import io.github.matthewjones372.surefold.Counter
import io.github.matthewjones372.surefold.Deferred
import io.github.matthewjones372.surefold.Gauge
import io.github.matthewjones372.surefold.Metrics
import io.github.matthewjones372.surefold.Schedule
import io.github.matthewjones372.surefold.Scope
import io.github.matthewjones372.surefold.VirtualThreads
import io.github.matthewjones372.surefold.clock
import io.github.matthewjones372.surefold.fail.Either
import io.github.matthewjones372.surefold.fail.Fail
import io.github.matthewjones372.surefold.fail.left
import io.github.matthewjones372.surefold.fail.nonFatalOrThrow
import io.github.matthewjones372.surefold.fail.right
import io.github.matthewjones372.surefold.increment
import io.github.matthewjones372.surefold.logDebug
import io.github.matthewjones372.surefold.metricTags
import io.github.matthewjones372.surefold.metrics
import java.time.Instant
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicIntegerFieldUpdater
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration

/**
 * Spawns [behaviour] in this scope, on the scope's executor. The actor cannot outlive the scope: closing it stops
 * the actor, after any turn already running has returned. A failure asks [restart], which restarts the actor from
 * its initial state after its delay on this scope's clock, or stops it once done; with no schedule it stops. Its
 * stash keeps at most [stash] messages, and so do its children's. An [urgent] actor is run before any other the
 * scope's runners have waiting (spec 0104): for the few whose lateness the whole node pays for, such as the cluster's.
 */
fun <F, M : Any, S, E> Scope<F>.spawn(
    name: String,
    behaviour: Behaviour<M, S, E>,
    capacity: Int = 1024,
    throughput: Int = 64,
    restart: Schedule<Failure<E>, *>? = null,
    stash: Int = 1024,
    urgent: Boolean = false,
): ActorRef<M> {
    require(capacity > 0) { "capacity must be positive, was $capacity" }
    require(stash >= 0) { "stash must not be negative, was $stash" }
    require(throughput > 0) { "throughput must be positive, was $throughput" }
    val guardian = guardian()
    val address = Address("local", "/user/$name", incarnations.incrementAndGet())
    return Cell(behaviour, address, capacity, throughput, stash, guardian, restart, parent = null)
        .also { it.urgent = urgent }
        .also { guardian.cells += it }
        .also { it.begin() }
}

/**
 * [message] told only if there is room for it, for a router passing over a full routee: false when the mailbox is
 * full or the actor has stopped. A test actor always has room.
 */
internal fun <M : Any> ActorRef<M>.offer(message: M): Boolean {
    @Suppress("UNCHECKED_CAST")
    val cell = this as? Cell<M, *, *> ?: return true.also { tell(message) }
    return cell.offer(message)
}

/**
 * [message] told if this has room, or has stopped, where a tell is a dead letter: false only when it is an actor's
 * full mailbox, and a tell from a turn would throw (spec 0095). An entity's ref asks its manager.
 */
@PlumbingSeam
fun <M : Any> ActorRef<M>.tellIfRoom(message: M): Boolean = when (this) {
    is Cell<*, *, *> -> {
        @Suppress("UNCHECKED_CAST")
        val cell = this as Cell<M, *, *>
        cell.offer(message) || (cell.isStopped && true.also { cell.tell(message) })
    }

    is EntityRef<M> -> manager.tellIfRoom(Deliver(id, message))

    else -> true.also { tell(message) }
}

/** The actors registered under [key] in this scope now. */
fun <F, M : Any> Scope<F>.find(key: ServiceKey<M>): Set<ActorRef<M>> = guardian().receptionist.find(key)

/**
 * Hands every [DeadLetter] of this scope's actors to [handler], in place of the default, which logs each at debug.
 * It runs on the thread that found the letter, an actor's or a sender's, so it should be quick and must not throw.
 */
fun <E> Scope<E>.onDeadLetter(handler: (DeadLetter) -> Unit) {
    guardian().deadLetters = handler
}

/** Keeps this scope's actors' events in [journal]. */
fun <E> Scope<E>.journal(journal: Journal) {
    guardian().journal = journal
}

/** The journal this scope's actors keep their events in; it fails when the scope has been given none. */
fun <E> Scope<E>.journal(): Journal = guardian().journalOrFail()

/** Keeps this scope's actors' snapshots in [store]; a scope given none takes none (spec 0074). */
fun <E> Scope<E>.snapshots(store: SnapshotStore) {
    guardian().snapshots = store
}

/** The store this scope's actors keep their snapshots in, or null when it has been given none. */
fun <E> Scope<E>.snapshots(): SnapshotStore? = guardian().snapshots

/** This scope's guardian, standing from the first call. */
private fun Scope<*>.guardian(): Guardian = checkNotNull(standing()) { "this scope has closed, and its actors with it" }

/**
 * This scope's guardian, standing from the first call, or null once the scope has closed. The closed check is made
 * inside the map's own update, so a thread that races the close either finds the guardian or finds the scope closed.
 */
private fun Scope<*>.standing(): Guardian? =
    // The scope's clock, read here once: an activation runs on a thread the scope did not fork, so it would not
    // inherit it. Its actors' restarts and timers all wait on it.
    guardians.computeIfAbsent(this) { scope ->
        if (scope in closed) null else Guardian(scope, on, clock.get()).also { g -> async { g.stand() } }
    }

/**
 * Scopes whose actors have all stopped, held weakly: a dead letter told to one afterwards, from a thread it did not
 * fork, must not stand a second guardian on it.
 */
private val closed: MutableSet<Scope<*>> = Collections.synchronizedSet(Collections.newSetFromMap(WeakHashMap()))

/**
 * Waits until every message told to this scope's actors, and every message those caused, has been handled or
 * dropped. The runtime counts them, so this parks until the count reaches zero rather than polling.
 */
fun <E> Scope<E>.awaitIdle() {
    guardians[this]?.backlog?.awaitSettled()
}

/**
 * [Signal.Terminated] once [ref] has stopped, for code outside any actor. Waits on the actor's own end rather than
 * a fork, so watching holds no thread until someone awaits it.
 */
@Suppress("UnusedReceiverParameter")
fun <F> Scope<F>.watch(ref: ActorRef<*>): Deferred<Signal.Terminated> {
    val ended = when (ref) {
        is Cell<*, *, *> -> ref.ended
        is Watchable -> CountDownLatch(1).also { latch -> ref.onTerminated(latch::countDown) }
        else -> throw IllegalArgumentException("$ref is not an actor on threads, so it cannot be watched")
    }
    return object : Deferred<Signal.Terminated> {
        override fun await(): Signal.Terminated {
            ended.await()
            return Signal.Terminated(ref)
        }

        override fun cancel() = Unit
    }
}

/**
 * Hands [letter] to this scope's dead-letter handler, as the runtime's own letters are: for a transport or a bridge
 * that found a message it could not deliver.
 */
fun <E> Scope<E>.deadLetter(letter: DeadLetter) {
    // After the scope has closed there is no handler left to hand it to.
    val guardian = standing()
    if (guardian == null) logDebug("dead letter after its scope closed: $letter") else guardian.dead(letter)
}

/**
 * The counter [name] of this scope (spec 0081): with the tags bound where the scope's actors first stood, those
 * [tagMetrics] added since, and [tags]. Measured through the `metrics` bound there, since an actor's turn runs on a
 * thread the scope did not fork and would not inherit them. Held once looked up, so a hot path may call this.
 */
fun Scope<*>.counter(name: String, vararg tags: Pair<String, String>): Counter =
    // A closed scope measures nothing: a transport's thread may still count after it has gone.
    standing()?.counter(name, tags) ?: Counter { }

/** The gauge [name] of this scope, as [counter]. */
fun Scope<*>.gauge(name: String, vararg tags: Pair<String, String>): Gauge =
    standing()?.gauge(name, tags) ?: Gauge { }

/**
 * Adds [tags] to every metric of this scope from now on, such as the name of the node it runs: an instrument looked
 * up before keeps the tags it had.
 */
fun Scope<*>.tagMetrics(vararg tags: Pair<String, String>) = guardian().tag(tags)

/**
 * Stops [ref] from outside, as the scope's close would: at once when it is idle, after its running turn otherwise.
 * The answer is its [Signal.Terminated], once it has stopped.
 */
fun <F> Scope<F>.stop(ref: ActorRef<*>): Deferred<Signal.Terminated> {
    val cell = requireNotNull(ref as? Cell<*, *, *>) { "$ref is not an actor on threads, so it cannot be stopped here" }
    cell.stop()
    return watch(ref)
}

/** How many of this scope's actors have not yet ended. */
internal val Scope<*>.actorCount: Int get() = guardians[this]?.cells?.size ?: 0

/** How many of this actor's children have not yet ended. */
internal val ActorRef<*>.childCount: Int get() = (this as Cell<*, *, *>).childCount

/**
 * Waits for the latch however often the thread is interrupted, and interrupts it again afterwards. An actor's end
 * must complete once begun: an interrupt that cut it short would leave its watchers and its scope waiting.
 */
private fun CountDownLatch.awaitThroughInterrupts() {
    var interrupted = false
    while (count > 0L) {
        try {
            await()
        } catch (again: InterruptedException) {
            interrupted = true
        }
    }
    if (interrupted) Thread.currentThread().interrupt()
}

/** A signal on its way through a mailbox. */
private class Signalled(val signal: Signal)

/** A message told with something to carry to its handler (spec 0122); one told with nothing is enqueued bare. */
private class Carried(val message: Any, val carried: Map<String, String>)

/** [message] as it goes into a mailbox: wrapped only when a carrier has something to send with it. */
private fun carrying(message: Any): Any {
    val carried = Carriers.capture()
    return if (carried.isEmpty()) message else Carried(message, carried)
}

/** The message a mailbox item holds, whether or not it carries anything. */
private fun bare(item: Any): Any = if (item is Carried) item.message else item

/** A listing from the receptionist on its way through a mailbox: nobody told it, so it takes no room. */
private class Listed(val message: Any)

/** Wakes a new actor whose behaviour has a start, so that it runs before anything is told to it. */
private object Started

/** Asks and waits on the calling thread. An actor that stops before replying answers [AskFailure.Stopped] at once. */
fun <M : Any, A : Any> ActorRef<M>.ask(within: Duration, message: (Reply<A>) -> M): Either<AskFailure, A> {
    // A test actor has handled the message before its tell returns, so there is nothing to wait for.
    if (this is TestActor<M, *, *>) return ask(message)
    val reply = Answer<A>(Address(address.node, "/temp/ask-${asks.incrementAndGet()}", 1))
    val cell = this as? Cell<M, *, *>
    if (cell != null && !cell.expect(reply)) return AskFailure.Stopped.left()
    tell(message(reply))
    val answered = reply.done.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)
    cell?.forget(reply)
    return if (answered) reply.outcome.get() else AskFailure.TimedOut.left()
}

private val incarnations = AtomicLong()

/** How long an actor spins for a reply before parking: past the knee of the ping-pong benchmark, 5 µs to 50 µs. */
private val LINGER_NANOS = TimeUnit.MICROSECONDS.toNanos(20)
private val asks = AtomicLong()

/**
 * The most runners one scope's actors share. Runners beyond one per carrier are started only while others are parked
 * in blocking turns, so this bounds how many turns may block at once: far above Pekko's blocking dispatcher, and
 * far above any burst the benchmarks or a service have shown.
 */
private const val RUNNERS = 2048

/** One runner per carrier: the JDK's own count, which a service may have set. */
private val PARALLELISM =
    Integer.getInteger("jdk.virtualThreadScheduler.parallelism", Runtime.getRuntime().availableProcessors())

/** The actor whose turn this thread is running, so a `tell` from inside one can refuse to wait. */
private val stepping = ThreadLocal<Cell<*, *, *>>()

/**
 * One guardian per scope: a single parked fork that the scope interrupts on close, which then stops its actors.
 * Keyed by the scope itself, since `Scope` has no hook of its own for close.
 */
// Its value type is nullable only so that a lookup may decline to stand a guardian on a closed scope: a
// ConcurrentHashMap never holds a null.
private val guardians = ConcurrentHashMap<Scope<*>, Guardian?>()

private val SCHEDULED: AtomicIntegerFieldUpdater<Cell<*, *, *>> =
    AtomicIntegerFieldUpdater.newUpdater(Cell::class.java, "scheduledFlag")

private val ROOM: AtomicIntegerFieldUpdater<Cell<*, *, *>> =
    AtomicIntegerFieldUpdater.newUpdater(Cell::class.java, "room")

/**
 * Activations running or scheduled across one scope's actors. A turn's tell schedules its target before its own
 * activation ends, so the count reaches zero only once nothing is left to handle.
 */
private class Backlog(private val runners: Runners?) {
    private val count = AtomicLong()
    private val lock = ReentrantLock()
    private val empty = lock.newCondition()

    // On virtual threads the runners know when the scope is idle, so no activation touches a shared count.
    private val counting = runners == null

    fun added() {
        if (counting) count.incrementAndGet()
    }

    fun done() {
        if (counting && count.decrementAndGet() == 0L) lock.withLock { empty.signalAll() }
    }

    fun awaitEmpty() {
        if (runners != null) return runners.awaitIdle()
        lock.withLock {
            while (count.get() != 0L) empty.await()
        }
    }

    // Messages surefold's own plumbing keeps for a busy receiver (spec 0095): told, and not yet handled or dropped.
    private val held = AtomicLong()
    private val released = lock.newCondition()

    fun held(delta: Int) {
        if (held.addAndGet(delta.toLong()) == 0L) lock.withLock { released.signalAll() }
    }

    /** Idle, with nothing kept for a busy receiver: what [awaitIdle] promises. The wheel settles on [awaitEmpty]. */
    fun awaitSettled() {
        while (true) {
            awaitEmpty()
            if (held.get() == 0L) return
            lock.withLock {
                while (held.get() != 0L) released.await()
            }
        }
    }
}

/** One scope's actors, the executor they run on and the clock they wait on. */
private class Guardian(private val scope: Scope<*>, val on: Executor, val clock: Clock) {
    // The actors that have not yet ended: each leaves as it ends, so a scope that outlives many holds none of them.
    val cells: MutableSet<Cell<*, *, *>> = ConcurrentHashMap.newKeySet()
    val runners = Runners(PARALLELISM, RUNNERS)
    val backlog = Backlog(runners.takeIf { on === VirtualThreads })
    val wheel = Wheel(clock) { backlog.awaitEmpty() }
    val receptionist = Receptionist()

    @Volatile
    var journal: Journal? = null

    fun journalOrFail(): Journal = checkNotNull(journal) { "no journal: give the scope one with journal(…)" }

    @Volatile
    var snapshots: SnapshotStore? = null

    @Volatile
    var deadLetters: (DeadLetter) -> Unit = { letter -> logDebug("dead letter: $letter") }

    // The metrics and tags where the scope's actors first stood: an activation runs on a runner, which inherits
    // neither. Instruments are held by name and tags, so counting a dead letter is a map hit and no allocation.
    private val measured: Metrics = metrics.get()

    @Volatile
    private var tags: Map<String, String> = metricTags.get()
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, Counter>()
    private val gauges = ConcurrentHashMap<Pair<String, Map<String, String>>, Gauge>()

    // Made again whenever the tags change, so a count taken before a node was named is not kept under no name.
    @Volatile
    private var tallies = Tallies()

    val restarts: Counter get() = tallies.restarts

    /** The guardian's own counters under the tags as they are now, each resolved on first use. */
    private inner class Tallies {
        val dead = DeadLetter.Why.entries.associateWith { why ->
            lazy { counter("surefold.actor.dead_letters", arrayOf("reason" to why.name.lowercase())) }
        }
        val restarts: Counter by lazy { counter("surefold.actor.restarts", emptyArray()) }
    }

    fun dead(letter: DeadLetter) {
        tallies.dead.getValue(letter.why).value.increment()
        deadLetters(letter)
    }

    fun counter(name: String, extra: Array<out Pair<String, String>>): Counter {
        val key = name to (tags + extra)
        return counters.computeIfAbsent(key) { measured.counter(it.first, it.second) }
    }

    fun gauge(name: String, extra: Array<out Pair<String, String>>): Gauge {
        val key = name to (tags + extra)
        return gauges.computeIfAbsent(key) { measured.gauge(it.first, it.second) }
    }

    fun tag(more: Array<out Pair<String, String>>) {
        tags = tags + more
        tallies = Tallies()
    }

    fun stand() {
        try {
            CountDownLatch(1).await()
        } catch (closing: InterruptedException) {
            // The scope is closing, which is the only way out of here.
        }
        wheel.close()
        cells.forEach { it.stop() }
        cells.forEach { it.ended.await() }
        // Marked closed before the guardian goes: a thread the scope did not fork, such as a transport's, that looks
        // for it afterwards finds the scope closed, rather than no guardian and a scope it would stand a second one on.
        closed += scope
        guardians.remove(scope)
    }
}

private class Answer<A : Any>(override val address: Address) : Reply<A>, Unanswerable {
    val outcome = AtomicReference<Either<AskFailure, A>>()
    val done = CountDownLatch(1)

    override fun invoke(answer: A) {
        check(complete(answer.right())) { "a second reply, $answer, after ${outcome.get()}" }
    }

    // Ignored when the answer came first: whichever completes the outcome first is the ask's.
    override fun unanswered(why: AskFailure) {
        complete(why.left())
    }

    fun complete(outcome: Either<AskFailure, A>): Boolean =
        this.outcome.compareAndSet(null, outcome).also { if (it) done.countDown() }
}

/** Whether an actor on threads can watch this ref: one of its own, or one that is [Watchable]. */
internal fun ActorRef<*>.canBeWatched() = this is Cell<*, *, *> || this is Watchable

private class Cell<M : Any, S, E>(
    private val behaviour: Behaviour<M, S, E>,
    override val address: Address,
    private val capacity: Int,
    private val throughput: Int,
    private val stashCapacity: Int,
    private val guardian: Guardian,
    restart: Schedule<Failure<E>, *>?,
    private val parent: Cell<*, *, *>?,
) : Mailbox(), ActorRef<M>, Ctx<M>, Timers<M>, Fired, Activation, DeadLetters, Restartable {
    override val self: ActorRef<M> get() = this

    // Whether the start to come follows a restart, a failure's or a wrong guess's (spec 0198).
    override var restarted = false
        private set

    override fun deadLetter(letter: DeadLetter) = guardian.dead(letter)

    override fun kept(delta: Int) = backlog.held(delta)

    override val timers: Timers<M> get() = this

    override val journal: Journal get() = guardian.journalOrFail()

    override val snapshots: SnapshotStore? get() = guardian.snapshots

    // Messages, and signals wrapped in Signalled so that no message type can be taken for one.
    // The mailbox is the cell itself (see Mailbox). The room left in it, and whether an activation holds the actor,
    // are fields of the cell for the same reason: a tell to a cold actor touches one object, not five.
    @Volatile
    @JvmField
    var room: Int = capacity

    @Volatile
    @JvmField
    var scheduledFlag: Int = 0

    // Senders parked on a full mailbox, made by the first to park.
    @Volatile
    private var roomWaiters: ConcurrentLinkedQueue<Thread>? = null

    // Held from the moment a message finds the actor idle until its activation ends, so one turn runs at a time.
    @Volatile
    private var stopped = false

    val isStopped: Boolean get() = stopped

    @Volatile
    private var state: S = behaviour.initial
    private val boundary = StepFail<E>()

    // Where the restart schedule has got to; it moves on with each failure.
    @Volatile
    private var supervision = restart?.step
    private val pending = ConcurrentHashMap.newKeySet<Answer<*>>()
    val ended = CountDownLatch(1)
    private val watchers = ConcurrentHashMap.newKeySet<Cell<*, *, *>>()

    @Volatile
    private var terminated = false

    // The runner running this actor's activation, for a stop to interrupt. A runner moves on to other actors, so it is
    // set, cleared and interrupted only under this cell's monitor: an interrupt can never land on another actor. Each
    // activation clears only the thread it set, since the next can set its own before the last has finished.
    private var running: Thread? = null
    private val backlog = guardian.backlog

    // Spawned by this actor's turns, so stopped before it. Only its own activation adds to it, and each child takes
    // itself out as it ends.
    private val children: MutableSet<Cell<*, *, *>> = ConcurrentHashMap.newKeySet()

    val childCount: Int get() = children.size

    // The timer running under each key. Only this actor's activation, or its end, reads or writes it. Made with
    // the first timer, so an actor that never starts one does not carry an empty map.
    @Suppress("DoubleMutabilityForCollection")
    private var armed: HashMap<Any, Timer>? = null
    private val wheel = guardian.wheel

    // The receive timeout, when one is on. Like the timers, only this actor's activation or its end touches it.
    private var silence: Silence? = null

    // The timers of the state the actor is in, and those of a `ctx.become` in the turn now running, which are the
    // state's only if the turn returns it.
    private var scoped: TimersOfState? = null
    private var asked: TimersOfState? = null

    override fun <T> become(state: T, timers: StateTimers<M>.() -> Unit): Next<T> {
        asked?.let(::drop)
        asked = startTimers(state, timers, ::start)
        return Next.Become(state)
    }

    /** The turn returned [next]: timers it asked for are the new state's if it is theirs, and cancelled if not. */
    private fun settled(next: Next<S>) {
        val asking = asked.also { asked = null }
        val becoming = (next as? Next.Become<S>)?.state
        val current = scoped
        when {
            next !is Next.Become -> asking?.let(::drop)

            asking != null && asking.state === becoming -> {
                current?.let(::drop)
                scoped = asking
            }

            else -> {
                asking?.let(::drop)
                if (current != null && current.endsAt(becoming)) {
                    drop(current)
                    scoped = null
                }
            }
        }
    }

    private fun drop(timers: TimersOfState) = timers.keys.forEach(::cancel)

    // Made with the first message kept, so an actor that never stashes does not carry one.
    private var stashed: Stash? = null

    override fun stash(message: M) {
        // Kept with what it carried, which is bound while it is being handled, so its replay is in the same trace.
        (stashed ?: Stash(stashCapacity, address.path).also { stashed = it }).keep(carrying(message))
    }

    override fun unstashAll() {
        stashed?.unstashAll()
    }

    /** Whether there is anything to handle: a message put back from the stash, or one in the mailbox. */
    private fun hasWork(): Boolean = starting || stashed?.isReplaying() == true || isNotEmpty()

    // Whether the behaviour's start is still to run: set on spawn and on each restart, and read by the activation.
    private var starting = false

    /** Schedules the behaviour's start, if it has one, ahead of anything told to the actor. */
    fun begin() {
        if (behaviour.start == null) return
        starting = true
        enqueue(Started)
    }

    private fun started(): Next<S> {
        starting = false
        val start = behaviour.start ?: return Next.Stay
        return supervised { start(this, this@Cell, state) }
    }

    override fun register(key: ServiceKey<M>) = guardian.receptionist.register(key, this)

    override fun <K : Any> subscribe(key: ServiceKey<K>, listing: (Set<ActorRef<K>>) -> M) =
        guardian.receptionist.subscribe(key, this) { refs ->
            @Suppress("UNCHECKED_CAST")
            if (!stopped) enqueue(Listed(listing(refs as Set<ActorRef<K>>)))
        }

    override fun after(key: Any, delay: Duration, message: M) = start(key, delay, message, null)

    override fun every(key: Any, interval: Duration, message: M) {
        require(interval.isPositive()) { "a periodic timer needs a positive interval, was $interval" }
        start(key, interval, message, interval)
    }

    override fun receiveTimeout(after: Duration, message: M) {
        silence = Silence(after, message)
        start(ReceiveTimeoutKey, after, message, null)
    }

    override fun receiveTimeout(off: Nothing?) {
        silence = null
        cancel(ReceiveTimeoutKey)
    }

    private fun start(key: Any, delay: Duration, message: Any, every: Duration?) {
        cancel(key)
        val timer = if (delay.isPositive()) {
            wheel.schedule(delay, key, message, this, every)
        } else {
            Timer(Instant.EPOCH, 0, key, message, this, every).also(::enqueue)
        }
        (armed ?: HashMap<Any, Timer>().also { armed = it })[key] = timer
    }

    /** A message was handled, so the silence the receive timeout waits for starts again. */
    private fun heard() {
        silence?.let { start(ReceiveTimeoutKey, it.after, it.message, null) }
    }

    /**
     * Handles the timer's message. Unless the turn cancelled or replaced it, a periodic one then starts again from
     * now, and any other is done.
     */
    private fun timed(timer: Timer): Next<S> {
        val next = turned(timer.message)
        val live = armed
        if (live != null && live[timer.key] === timer) {
            val every = timer.every
            if (every == null) {
                live.remove(timer.key)
            } else {
                live[timer.key] = wheel.schedule(every, timer.key, timer.message, this, every)
            }
        }
        if (timer.key !== ReceiveTimeoutKey) heard()
        return next
    }

    override fun cancel(key: Any) {
        armed?.remove(key)?.let(wheel::cancel)
    }

    /** A timer falls due: it takes no room, since the wheel must not wait on any one actor. */
    override fun fire(timer: Timer) {
        if (!stopped) enqueue(timer)
    }

    /** Cancels every timer and drops the stash: nothing of either outlives a restart or a stop. */
    private fun letGo() {
        guardian.receptionist.forget(this)
        armed?.values?.forEach(wheel::cancel)
        armed = null
        silence = null
        scoped = null
        asked = null
        stashed = null
    }

    override fun <C : Any, T, F> spawn(
        name: String,
        behaviour: Behaviour<C, T, F>,
        restart: Schedule<Failure<F>, *>?,
    ): ActorRef<C> {
        val child = Cell(
            behaviour,
            Address(address.node, "${address.path}/$name", incarnations.incrementAndGet()),
            capacity,
            throughput,
            stashCapacity,
            guardian,
            restart,
            parent = this,
        )
        guardian.cells += child
        children += child
        child.begin()
        return child
    }

    /** Stops every child and waits for each to end, so none outlives or overlaps this actor's own ending. */
    private fun stopChildren() {
        children.forEach { child ->
            child.stop()
            child.ended.awaitThroughInterrupts()
        }
    }

    // Whether this activation told another actor anything, so that a reply may be on its way. Read and written
    // only by the thread running this actor's activation.
    var told = false

    // Whether the runners take this actor's activations before any other (spec 0104). Set once, before it begins.
    var urgent = false

    /**
     * A message told once the actor has stopped is a dead letter, and so is one still in the mailbox when it stops.
     * One told in the instant the actor stops may be neither handled nor reported.
     */
    override fun tell(message: M) {
        if (stopped) return dead(message, DeadLetter.Why.Stopped)
        val sender = stepping.get()
        sender?.told = true
        if (sender == null) {
            acquireRoom()
        } else {
            check(tryAcquireRoom()) { "the mailbox of ${address.path} is full" }
        }
        if (stopped) return dead(message, DeadLetter.Why.Stopped)
        enqueue(carrying(message))
    }

    fun offer(message: M): Boolean {
        if (stopped) return false
        stepping.get()?.told = true
        if (!tryAcquireRoom()) return false
        if (stopped) return false
        enqueue(carrying(message))
        return true
    }

    private fun tryAcquireRoom(): Boolean {
        while (true) {
            val left = room
            if (left <= 0) return false
            if (ROOM.compareAndSet(this, left, left - 1)) return true
        }
    }

    /** Takes room for one message, parking while the mailbox is full; interruptible, as a semaphore's acquire is. */
    private fun acquireRoom() {
        if (Thread.interrupted()) throw InterruptedException()
        while (!tryAcquireRoom()) awaitRoom()
    }

    private fun awaitRoom() {
        val waiters = roomWaiters ?: synchronized(this) {
            roomWaiters ?: ConcurrentLinkedQueue<Thread>().also { roomWaiters = it }
        }
        val me = Thread.currentThread()
        waiters.add(me)
        try {
            // Re-read after joining the waiters: a release after this read sees this thread among them.
            if (room <= 0) LockSupport.park(this)
            if (Thread.interrupted()) throw InterruptedException()
        } finally {
            waiters.remove(me)
        }
    }

    private fun releaseRoom(count: Int) {
        ROOM.getAndAdd(this, count)
        roomWaiters?.forEach(LockSupport::unpark)
    }

    private fun dead(message: Any, why: DeadLetter.Why) = guardian.dead(DeadLetter(address, message, why))

    /** A signal takes no room: the actor that sends one may be ending, and must not wait to. */
    fun signal(signal: Signal) {
        if (!stopped) enqueue(Signalled(signal))
    }

    override fun watch(ref: ActorRef<*>) {
        when (ref) {
            is Cell<*, *, *> -> ref.watchedBy(this)
            is Watchable -> ref.onTerminated { signal(Signal.Terminated(ref)) }
            else -> throw IllegalArgumentException("$ref is not an actor on threads, so it cannot be watched from one")
        }
    }

    override fun stop(child: ActorRef<*>) {
        // A child that has already ended is no longer among the children, and stopping it again does nothing.
        val cell = child as? Cell<*, *, *>
        require(cell?.parent === this) { "$child is not a child of ${address.path}, so it cannot stop it" }
        cell.stop()
    }

    /** Whichever of this and [finish] takes [watcher] out of the set delivers its one `Terminated`. */
    fun watchedBy(watcher: Cell<*, *, *>) {
        if (watchers.add(watcher) && terminated &&
            watchers.remove(watcher)
        ) watcher.signal(Signal.Terminated(this))
    }

    private fun enqueue(item: Any) {
        add(item)
        // Stopped between the check and the add: nothing will handle it, and the stop has let the mailbox go.
        if (stopped) return
        // Read before the compare-and-set: a busy actor's flag is almost always taken, and a failed CAS still
        // takes the cache line away from the actor's own thread.
        if (scheduledFlag == 0 && SCHEDULED.compareAndSet(this, 0, 1)) {
            backlog.added()
            schedule()
        }
    }

    /** Hands the activation to the scope's runners on virtual threads, and to the executor otherwise. */
    private fun schedule() {
        if (guardian.on === VirtualThreads) guardian.runners.submit(this, urgent) else guardian.on.execute(::activate)
    }

    /** Registers an ask, so that stopping answers it. False when the actor has already stopped. */
    fun expect(reply: Answer<*>): Boolean {
        pending.add(reply)
        return !stopped
    }

    fun forget(reply: Answer<*>) {
        pending.remove(reply)
    }

    /**
     * Stops the actor: at once when it is idle, and after its running turn otherwise. On virtual threads that turn is
     * interrupted, since each activation has a thread of its own that nothing else will run on; on any other executor
     * an interrupt could land on the pool's next task, so the turn is waited for.
     */
    fun stop() {
        stopped = true
        if (SCHEDULED.compareAndSet(this, 0, 1)) {
            finish()
        } else if (guardian.on === VirtualThreads) {
            synchronized(this) { running?.interrupt() }
        }
    }

    /** One activation, counted once in the scope's backlog however many messages it handles. */
    override fun activate() {
        var returned = false
        try {
            stepping.set(this)
            synchronized(this) { running = Thread.currentThread() }
            run()
            returned = true
        } catch (interrupted: InterruptedException) {
            // The interrupt a stop sends to a running turn: the stop is the outcome, not a failure to report.
            if (!stopped) throw interrupted
        } finally {
            // An interrupt a stop sent as the turn returned is this actor's, and goes with it. Once this activation
            // has let go of `scheduled`, the next may already be running on another runner: that one is a stop's to
            // interrupt, so this one clears only its own.
            synchronized(this) {
                if (running === Thread.currentThread()) running = null
                Thread.interrupted()
            }
            stepping.remove()
            // A throw stops the actor, and still reaches the thread's own handler.
            if (!returned) {
                stopped = true
                finish()
                backlog.done()
            }
        }
    }

    /**
     * Handles `throughput` messages at a time. Between batches the actor goes to the back of the queue it came from,
     * the scope's runners or its executor, so a busy actor is fair to the others.
     */
    private tailrec fun run() {
        drain(throughput)
        when {
            stopped -> {
                finish()
                backlog.done()
            }

            hasWork() -> schedule()

            lingered() -> run()

            else -> {
                scheduledFlag = 0
                // A message that arrived after the last poll and lost the race for `scheduled` is ours to handle.
                if ((hasWork() || stopped) && SCHEDULED.compareAndSet(this, 0, 1)) {
                    run()
                } else {
                    backlog.done()
                }
            }
        }
    }

    /**
     * Spins for up to [LINGER_NANOS] waiting for a message, before letting the thread go, when this activation
     * told another actor something: a reply is usually that close behind, and handling it here costs nothing where
     * parking costs a carrier's wake to come back. An actor that only receives never spins, so a fan-out does not
     * pay it once per actor.
     */
    private fun lingered(): Boolean {
        if (!told) return false
        told = false
        val deadline = System.nanoTime() + LINGER_NANOS
        while (System.nanoTime() < deadline) {
            if (hasWork() || stopped) return true
            Thread.onSpinWait()
        }
        return false
    }

    private tailrec fun drain(left: Int) {
        if (left == 0 || stopped) return
        val next = if (starting) started() else replayedOrPolled(left) ?: return
        when (next) {
            Next.Stay, Next.Unhandled -> Unit
            is Next.Become -> state = next.state
            Next.Stop -> stopped = true
        }
        settled(next)
        drain(left - 1)
    }

    /**
     * A message put back from the stash, which comes before the mailbox and took its room back when first polled, or
     * else the mailbox's next item; null when there is neither.
     */
    private fun replayedOrPolled(left: Int): Next<S>? {
        val replayed = stashed?.next()
        return if (replayed != null) turned(replayed).also { heard() } else poll()?.let { handled(it, left) }
    }

    /**
     * One item from the mailbox: a signal, a timer, a listing, the start's wake-up or a message. A message to a
     * behaviour with `turns` takes the plain messages behind it along, up to its batch and to what is [left] of this
     * activation's turn (spec 0086).
     */
    private fun handled(item: Any, left: Int): Next<S> = when (item) {
        Started -> Next.Stay

        is Listed -> turned(item.message).also { heard() }

        is Signalled -> {
            val handler = behaviour.signal
            if (handler == null) Next.Stay else supervised { handler(this, this@Cell, state, item.signal) }
        }

        // Only the timer still running under its key is handled: one cancelled or replaced since is dropped.
        is Timer -> if (armed?.get(item.key) === item) timed(item) else Next.Stay

        else -> {
            val turns = behaviour.turns
            if (turns == null || behaviour.batch <= 1 || left <= 1) {
                releaseRoom(1)
                turned(item).also { heard() }
            } else {
                ran(turns, item, minOf(behaviour.batch, left))
            }
        }
    }

    /** [first] and the plain messages waiting behind it, at most [most], as one run through [turns]. */
    @Suppress("UNCHECKED_CAST")
    private fun ran(turns: Fail<E>.(Ctx<M>, S, Run<M>) -> Batched<S>, first: Any, most: Int): Next<S> {
        val items = ArrayList<Any>(most)
        items += first
        while (items.size < most && isPlain(peek())) items += checkNotNull(poll())
        releaseRoom(items.size)
        val run = items.map(::bare)
        // What each message carried, listed only when one carried something, so a run in no trace allocates none.
        val carried = if (items.any { it is Carried }) items.map { (it as? Carried)?.carried.orEmpty() } else null
        var batched: Batched<S>? = null
        // One turn for the run, inside no message's context: each is the turn's to enter with `within` (spec 0223).
        val next = try {
            supervised {
                turns(this, this@Cell, state, Carrying(run as List<M>, carried)).also { batched = it }.next
            }
        } catch (_: WrongGuess) {
            return guessedWrong(items)
        }
        batched?.let { done ->
            done.unhandled.forEach { dead(it, DeadLetter.Why.Unhandled) }
            done.unrun.forEach { dead(it, DeadLetter.Why.Stopped) }
        }
        heard()
        return next
    }

    /** Whether [item] is a message told to this actor, rather than a signal, a timer, a listing or the start. */
    private fun isPlain(item: Any?): Boolean =
        item != null && item !== Started && item !is Listed && item !is Signalled && item !is Timer

    /** One message through `turn`, inside what it carried from its sender. */
    private fun turned(item: Any): Next<S> =
        try {
            if (item is Carried) Carriers.within(item.carried) { turn(item.message) } else turn(item)
        } catch (_: WrongGuess) {
            guessedWrong(listOf(item))
        }

    @Suppress("UNCHECKED_CAST")
    private fun turn(message: Any): Next<S> = supervised { behaviour.turn(this, this@Cell, state, message as M) }
        .also { if (it === Next.Unhandled) dead(message, DeadLetter.Why.Unhandled) }

    /**
     * A start that guessed the entity was new, shown wrong by its first command or run of commands: restarts as a
     * failure would, without asking supervision or counting a restart, and puts [items] back, in order, to be handled
     * first once the start has run.
     */
    private fun guessedWrong(items: List<Any>): Next<S> {
        stopChildren()
        letGo()
        state = behaviour.initial
        restarted = true
        starting = behaviour.start != null
        // Room for the whole run, whatever the stash's own capacity: these were already this actor's to handle.
        stashed = Stash(maxOf(stashCapacity, items.size), address.path).apply {
            items.forEach(::keep)
            unstashAll()
        }
        return Next.Stay
    }

    /**
     * Restarts from the initial state after the schedule's delay, keeping the mailbox, or stops once the schedule is
     * done. With no schedule a throw goes on to the thread, as it did before there was supervision.
     */
    private fun failed(failure: Failure<E>, thrown: Throwable?): Next<S> =
        when (val decision = supervision?.invoke(failure)) {
            is Schedule.Decision.Continue -> {
                stopChildren()
                letGo()
                guardian.restarts.increment()
                guardian.clock.sleep(decision.delay)
                state = behaviour.initial
                supervision = decision.step
                restarted = true
                starting = behaviour.start != null
                Next.Stay
            }

            else -> {
                if (thrown != null && supervision == null) throw thrown
                Next.Stop
            }
        }

    /** A turn or a signal's handler, its raise and its throw handed to supervision. Inline: nothing is allocated. */
    // A throw is the actor's failure, handed to its schedule like a raise; only a fatal one is not.
    @Suppress("TooGenericExceptionCaught")
    private inline fun supervised(body: Fail<E>.() -> Next<S>): Next<S> =
        try {
            boundary.guarded(body) { error -> failed(Failure.Declared(error), null) }
        } catch (wrong: WrongGuess) {
            throw wrong
        } catch (thrown: Throwable) {
            failed(Failure.Thrown(thrown.nonFatalOrThrow()), thrown)
        }

    /** Stops its children, signals [Signal.Stopping], then lets go; watchers hear `Terminated` before [ended] opens. */
    private fun finish() {
        stopChildren()
        val kept = stashed?.drain().orEmpty()
        letGo()
        stopping()
        kept.forEach { dead(bare(it), DeadLetter.Why.Stopped) }
        // Messages still waiting are dead letters; a signal, a timer or a listing is not one, since nobody told it.
        generateSequence { poll() }
            .filterNot { it is Signalled || it is Timer || it is Listed || it === Started }
            .forEach { dead(bare(it), DeadLetter.Why.Stopped) }
        // Wakes every sender parked on a full mailbox; each finds the actor stopped and drops its message.
        releaseRoom(Int.MAX_VALUE / 2)
        finishAsks()
        parent?.children?.remove(this)
        guardian.cells.remove(this)
        terminated = true
        watchers.forEach { if (watchers.remove(it)) it.signal(Signal.Terminated(this)) }
        ended.countDown()
    }

    // The actor is ending: a raise or a throw from its Stopping handler has no schedule left to go to.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun stopping() {
        val handler = behaviour.signal ?: return
        try {
            boundary.guarded({ handler(this, this@Cell, state, Signal.Stopping) }) { Next.Stop }
        } catch (thrown: Throwable) {
            thrown.nonFatalOrThrow()
        }
    }

    private fun finishAsks() {
        pending.forEach { it.complete(AskFailure.Stopped.left()) }
    }

    override fun equals(other: Any?): Boolean = other is ActorRef<*> && other.address == address

    override fun hashCode(): Int = address.hashCode()

    override fun toString(): String = "ActorRef(${address.path}#${address.incarnation})"
}
