package io.github.matthewjones372.surefold.actor

import dev.surefold.Clock
import dev.surefold.Schedule
import dev.surefold.ScheduleStep
import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.left
import dev.surefold.fail.nonFatalOrThrow
import dev.surefold.fail.right
import dev.surefold.fixedClock
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.random.Random
import kotlin.time.Duration

/** Runs this behaviour on the calling thread, for a test: no system, no threads, nothing to wait for. */
fun <M : Any, S, E> Behaviour<M, S, E>.test(
    name: String = "test",
    restart: Schedule<Failure<E>, *>? = null,
    stash: Int = 1024,
): TestActor<M, S, E> = TestActors().spawn(name, this, restart, stash)

/**
 * Several actors stepped by the test itself, each message delivered in the order it was told unless [order] says
 * otherwise. A restart waits on [clock], which by default does not wait: each delay is recorded in [TestActor.delays]
 * instead. Events go to [journal] and snapshots to [snapshots], both in memory by default, which the test can read.
 * [faults] draw from the order's seed, so they need an [Ordering.Seeded].
 */
fun <A> testActors(
    clock: Clock = fixedClock(),
    journal: Journal = InMemoryJournal(),
    snapshots: SnapshotStore = InMemorySnapshots(),
    order: Ordering = Ordering.Told,
    faults: Faults = Faults(),
    block: TestActors.() -> A,
): A = TestActors(clock, journal, snapshots, order, faults).block()

/**
 * The actors of one test. A tell from the test returns once that message, and every message it caused, has been
 * handled; a tell from inside a turn joins the queue behind whatever is already on it.
 */
class TestActors internal constructor(
    private val clock: Clock = fixedClock(),
    /** Where this test's actors keep their events, for the test to read. */
    val journal: Journal = InMemoryJournal(),
    /** Where this test's actors keep their snapshots, for the test to read. */
    val snapshots: SnapshotStore = InMemorySnapshots(),
    order: Ordering = Ordering.Told,
    private val faults: Faults = Faults(),
) {
    init {
        require(!faults.any || order is Ordering.Seeded) {
            "faults draw from the order's seed: give an Ordering.seeded"
        }
    }

    private class Delivery(val from: Address?, val to: TestActor<*, *, *>, val item: Any)

    private val queue = AtomicReference<List<Delivery>>(emptyList())
    private val draining = AtomicBoolean(false)

    private val picks = when (order) {
        Ordering.Told -> null
        is Ordering.Seeded -> Random(order.seed)
    }

    // The actor whose turn is running, which is who a tell made now is from.
    private val stepping = AtomicReference<TestActor<*, *, *>?>()

    private val steps = AtomicLong()

    // The step past which settle gives up, and the bound it was given, while it runs.
    private val bound = AtomicReference<Pair<Long, Int>?>()

    private val traced = ConcurrentLinkedQueue<TraceEntry>()

    // An actor that crashed in its own turn, which goes down once that turn is over.
    private val crashing = AtomicReference<TestActor<*, *, *>?>()

    /** Every message delivered so far, in order. */
    val trace: List<TraceEntry> get() = traced.toList()
    private val incarnations = AtomicLong()

    // The test's own time, which moves only by [advance], and the timers still to fall due in it, soonest first.
    private val time = AtomicReference(Duration.ZERO)
    private val timers = AtomicReference<List<TestTimer>>(emptyList())
    private val order = compareBy<TestTimer>({ it.at }, { it.seq })

    /** How many timers are still to fall due, so that a test can say nothing is left. */
    val pendingTimers: Int get() = timers.get().size

    private val letters = AtomicReference<List<DeadLetter>>(emptyList())

    /** Every message nobody handled, in the order it was found: told to a stopped actor, or answered `unhandled()`. */
    val deadLetters: List<DeadLetter> get() = letters.get()

    internal val receptionist = Receptionist()

    /** The actors registered under [key] now. */
    fun <M : Any> find(key: ServiceKey<M>): Set<ActorRef<M>> = receptionist.find(key)

    internal fun dead(letter: DeadLetter) {
        letters.updateAndGet { it + letter }
    }

    /**
     * Moves the test's time on by [by], delivering each timer that falls due on the way in time order, and returns
     * once each has run to idle.
     */
    fun advance(by: Duration) {
        require(!by.isNegative()) { "time only moves forward, not by $by" }
        val until = time.get() + by
        generateSequence { timers.get().firstOrNull()?.takeIf { it.at <= until } }.forEach { timer ->
            timers.updateAndGet { it - timer }
            time.set(timer.at)
            when (val key = timer.key) {
                is InFlight -> enqueue(Delivery(key.from, timer.to, timer.message))
                else -> post(timer.to, timer)
            }
        }
        time.set(until)
    }

    /**
     * Runs every message and timer to idle, moving the test's time on to each timer as it falls due. Fails after
     * [maxSteps] deliveries, so that a livelock, or a periodic timer nothing cancels, fails rather than hangs.
     */
    fun settle(maxSteps: Int = 100_000) {
        check(!draining.get()) { "settle is for the test, not for a step" }
        bound.set(steps.get() + maxSteps to maxSteps)
        try {
            generateSequence { timers.get().firstOrNull() }.forEach { advance(it.at - time.get()) }
        } finally {
            bound.set(null)
        }
    }

    /**
     * Crashes [actor] as its node's death would: the messages waiting for it, its timers, stash and children are lost,
     * with no `Stopping`, and its next message starts it again from the journal and snapshots.
     */
    fun crash(actor: ActorRef<*>) {
        val crashed = requireNotNull(actor as? TestActor<*, *, *>) { "$actor is not a test actor, so it cannot crash" }
        check(!crashed.stopped) { "${actor.address.path} has stopped, so it cannot crash" }
        traced.add(TraceEntry.Crashed(actor.address))
        queue.updateAndGet { queued -> queued.filterNot { it.to === crashed } }
        if (stepping.get() === crashed) crashing.set(crashed) else crashed.crash()
    }

    /** A timer [delay] from now, waiting for [advance] when that is later, and on the queue now otherwise. */
    internal fun schedule(
        to: TestActor<*, *, *>,
        key: Any,
        delay: Duration,
        message: Any,
        every: Duration? = null,
    ): TestTimer = TestTimer(time.get() + delay, incarnations.incrementAndGet(), to, key, message, every)

    internal fun start(timer: TestTimer) {
        if (timer.at > time.get()) timers.updateAndGet { (it + timer).sortedWith(order) } else post(timer.to, timer)
    }

    internal fun unschedule(timer: TestTimer) {
        timers.updateAndGet { it - timer }
    }

    fun <M : Any, S, E> spawn(
        name: String,
        behaviour: Behaviour<M, S, E>,
        restart: Schedule<Failure<E>, *>? = null,
        stash: Int = 1024,
    ): TestActor<M, S, E> = spawnAt("/user/$name", behaviour, restart, stash)

    internal fun <M : Any, S, E> spawnAt(
        path: String,
        behaviour: Behaviour<M, S, E>,
        restart: Schedule<Failure<E>, *>?,
        stash: Int,
    ): TestActor<M, S, E> {
        require(stash >= 0) { "stash must not be negative, was $stash" }
        val actor =
            TestActor(this, behaviour, Address("test", path, incarnations.incrementAndGet()), restart, clock, stash)
        if (behaviour.start != null) post(actor, TestStarted)
        return actor
    }

    /** Returns at once: a tell from the test has already run to idle. Here so a scenario reads the same on threads. */
    fun awaitIdle() = Unit

    internal fun post(to: TestActor<*, *, *>, item: Any) {
        // A tell to itself never leaves the actor's node, so no fault reaches it.
        val between = stepping.get()?.takeIf { it !== to }?.address
        val random = picks?.takeIf { faults.any }
        val message = item !is TestTimer && item !is TestSignalled && item !== TestStarted
        if (between != null && random != null && message) {
            faulted(Delivery(between, to, item), between, random)
        } else {
            enqueue(Delivery(stepping.get()?.address, to, item))
        }
    }

    // The latest a message on each sender's way to each receiver is held back to: what follows it waits behind it.
    private val held = AtomicReference<Map<Pair<Address, TestActor<*, *, *>>, Duration>>(emptyMap())

    /** [delivery] now, or behind a message held back on the same way, so one sender's messages keep their order. */
    private fun deliver(delivery: Delivery, from: Address) {
        val until = held.get()[from to delivery.to]?.takeIf { it >= time.get() }
        if (until == null) {
            enqueue(delivery)
        } else {
            start(TestTimer(until, incarnations.incrementAndGet(), delivery.to, InFlight(from), delivery.item))
        }
    }

    private fun faulted(delivery: Delivery, from: Address, random: Random) {
        val to = delivery.to
        val roll = random.nextDouble()
        when {
            roll < faults.drop -> traced.add(TraceEntry.Dropped(from, to.address, delivery.item))

            roll < faults.drop + faults.duplicate -> {
                traced.add(TraceEntry.Duplicated(from, to.address, delivery.item))
                deliver(delivery, from)
                deliver(Delivery(from, to, delivery.item), from)
            }

            roll < faults.drop + faults.duplicate + faults.delay -> {
                val by = faults.maxDelay * (1.0 - random.nextDouble())
                traced.add(TraceEntry.Delayed(from, to.address, delivery.item, by))
                val at = maxOf(time.get() + by, held.get()[from to to] ?: Duration.ZERO)
                held.updateAndGet { it + ((from to to) to at) }
                start(TestTimer(at, incarnations.incrementAndGet(), to, InFlight(from), delivery.item))
            }

            roll < faults.drop + faults.duplicate + faults.delay + faults.crash -> {
                if (!to.stopped) crash(to)
                traced.add(TraceEntry.Dropped(from, to.address, delivery.item))
            }

            else -> deliver(delivery, from)
        }
    }

    private fun enqueue(delivery: Delivery) {
        queue.updateAndGet { it + delivery }
        if (draining.compareAndSet(false, true)) {
            try {
                drain()
            } finally {
                draining.set(false)
            }
        }
    }

    private tailrec fun drain() {
        val queued = queue.get()
        if (queued.isEmpty()) return
        val next = picks?.let { random ->
            val heads = queued.distinctBy { it.from to it.to }
            heads[random.nextInt(heads.size)]
        } ?: queued.first()
        queue.updateAndGet { it - next }
        val step = steps.incrementAndGet()
        bound.get()?.let { (last, max) -> check(step <= last) { "settle ran $max steps without going idle" } }
        traced(next)
        stepping.set(next.to)
        try {
            next.to.receive(next.item)
        } finally {
            stepping.set(null)
        }
        crashing.getAndSet(null)?.crash()
        drain()
    }

    private fun traced(delivery: Delivery) {
        val message = when (val item = delivery.item) {
            TestStarted -> return
            is TestTimer -> item.message
            is TestSignalled -> item.signal
            else -> item
        }
        traced.add(TraceEntry.Delivered(delivery.from, delivery.to.address, message))
    }
}

class TestActor<M : Any, S, E> internal constructor(
    private val scope: TestActors,
    private val behaviour: Behaviour<M, S, E>,
    override val address: Address,
    restart: Schedule<Failure<E>, *>?,
    private val clock: Clock,
    private val stashCapacity: Int,
) : ActorRef<M> {

    // The actor whose turn spawned this one, which lets it go as it ends.
    private var parent: TestActor<*, *, *>? = null

    // Made with the first message kept; only the test's own thread touches it.
    private val stashed = AtomicReference<Stash?>()

    private data class Run<M, S, E>(
        val state: S,
        val stopped: Boolean,
        val unhandled: List<M>,
        val failure: Failure<E>?,
        val supervision: ScheduleStep<Failure<E>, *>?,
        val delays: List<Duration>,
        val signals: List<Signal> = emptyList(),
        val watchers: Set<TestActor<*, *, *>> = emptySet(),
        val ended: Boolean = false,
        val children: List<TestActor<*, *, *>> = emptyList(),
        val timers: Map<Any, TestTimer> = emptyMap(),
        val silence: Silence? = null,
        val scoped: TimersOfState? = null,
        val asked: TimersOfState? = null,
    )

    private val run =
        AtomicReference(Run<M, S, E>(behaviour.initial, false, emptyList(), null, restart?.step, emptyList()))
    private val boundary = StepFail<E>()
    private val asks = AtomicLong()

    // Whether the start to come follows a restart, a failure's or a wrong guess's (spec 0198); a crash's does not.
    private val restarted = AtomicBoolean(false)

    private val ctx = object : Ctx<M>, DeadLetters, Restartable {
        override val self = this@TestActor

        override val restarted get() = this@TestActor.restarted.get()

        override fun deadLetter(letter: DeadLetter) = scope.dead(letter)

        // A test actor always has room, so nothing is ever kept for one.
        override fun kept(delta: Int) = Unit

        override val journal: Journal get() = scope.journal

        override val snapshots: SnapshotStore get() = scope.snapshots

        override val timers = object : Timers<M> {
            override fun after(key: Any, delay: Duration, message: M) = start(key, delay, message, null)

            override fun every(key: Any, interval: Duration, message: M) {
                require(interval.isPositive()) { "a periodic timer needs a positive interval, was $interval" }
                start(key, interval, message, interval)
            }

            override fun cancel(key: Any) = this@TestActor.cancel(key)
        }

        override fun receiveTimeout(after: Duration, message: M) {
            run.updateAndGet { it.copy(silence = Silence(after, message)) }
            start(ReceiveTimeoutKey, after, message, null)
        }

        override fun receiveTimeout(off: Nothing?) {
            run.updateAndGet { it.copy(silence = null) }
            cancel(ReceiveTimeoutKey)
        }

        override fun stash(message: M) {
            (stashed.get() ?: Stash(stashCapacity, address.path).also(stashed::set)).keep(message)
        }

        override fun register(key: ServiceKey<M>) = scope.receptionist.register(key, this@TestActor)

        override fun <K : Any> subscribe(key: ServiceKey<K>, listing: (Set<ActorRef<K>>) -> M) =
            scope.receptionist.subscribe(key, this@TestActor) { refs ->
                @Suppress("UNCHECKED_CAST")
                scope.post(this@TestActor, listing(refs as Set<ActorRef<K>>))
            }

        override fun unstashAll() {
            stashed.get()?.unstashAll()
        }

        override fun <T> become(state: T, timers: StateTimers<M>.() -> Unit): Next<T> {
            run.get().asked?.let(::drop)
            val asked = startTimers(state, timers, ::start)
            run.updateAndGet { it.copy(asked = asked) }
            return Next.Become(state)
        }

        override fun watch(ref: ActorRef<*>) =
            requireNotNull(ref as? TestActor<*, *, *>) { "$ref is not a test actor, so a test actor cannot watch it" }
                .watchedBy(this@TestActor)

        override fun stop(child: ActorRef<*>) {
            // A child that has already ended is no longer among the children, and stopping it again does nothing.
            val actor = child as? TestActor<*, *, *>
            require(actor?.parent === this@TestActor) {
                "$child is not a child of ${address.path}, so it cannot stop it"
            }
            actor.halt()
        }

        override fun <C : Any, T, F> spawn(
            name: String,
            behaviour: Behaviour<C, T, F>,
            restart: Schedule<Failure<F>, *>?,
        ): ActorRef<C> = scope.spawnAt("${address.path}/$name", behaviour, restart, stashCapacity).also { child ->
            child.parent = this@TestActor
            run.updateAndGet { it.copy(children = it.children + child) }
        }
    }

    val state: S get() = run.get().state

    val stopped: Boolean get() = run.get().stopped

    val unhandled: List<M> get() = run.get().unhandled

    /** Why the actor stopped, when it was a raise or a throw rather than `stop()`. */
    val failure: Failure<E>? get() = run.get().failure

    val restarts: Int get() = run.get().delays.size

    /** Every signal this actor has taken, in order. */
    val signals: List<Signal> get() = run.get().signals

    /** The children it has now; a stop or a restart takes them all. */
    val children: List<ActorRef<*>> get() = run.get().children

    /** The delay each restart waited, in order. */
    val delays: List<Duration> get() = run.get().delays

    /** The test's timers still to fall due, this actor's and every other's. */
    val pendingTimers: Int get() = scope.pendingTimers

    /** The test's dead letters, this actor's and every other's. */
    val deadLetters: List<DeadLetter> get() = scope.deadLetters

    /** Moves the test's time on; see [TestActors.advance]. */
    fun advance(by: Duration) = scope.advance(by)

    /** A message to a stopped actor is dropped, as it would be on threads. */
    override fun tell(message: M) = scope.post(this, message)

    /** [tell], for a test that means it: a send to a stopped actor is a mistake in the test and fails. */
    fun send(message: M) {
        check(!stopped) { "$message was sent to ${address.path}, which has stopped" }
        tell(message)
    }

    /** The reply, or why there is none. A message handled without a reply fails here, rather than timing out. */
    fun <A : Any> ask(message: (Reply<A>) -> M): Either<AskFailure, A> {
        if (stopped) return AskFailure.Stopped.left()
        val reply = TestReply<A>(Address(address.node, "/temp/ask-${asks.incrementAndGet()}", 1))
        val asked = message(reply)
        send(asked)
        val answer = reply.answer.get()
        return when {
            answer != null -> answer.right()
            stopped -> AskFailure.Stopped.left()
            else -> error("$asked was handled by ${address.path} and never replied")
        }
    }

    /** A message or a signal; whatever stops the actor, `Stopping` and its watchers' `Terminated` follow. */
    internal fun receive(item: Any) {
        if (stopped) {
            // A message, not a signal or a timer, that reached an actor which had stopped: nobody told those.
            if (item !is TestSignalled && item !is TestTimer && item !== TestStarted) dead(item, DeadLetter.Why.Stopped)
            return
        }
        try {
            if (down.getAndSet(false)) started()
            @Suppress("UNCHECKED_CAST")
            when (item) {
                is TestSignalled -> signalled(item.signal)

                is TestTimer -> timed(item)

                TestStarted -> started()

                else -> {
                    turned(item as M)
                    heard()
                }
            }
            // A restart while handling it: the start runs again before anything else reaches the actor.
            if (starting.get() && !stopped) started()
            replayed()
        } finally {
            if (stopped) ended()
        }
    }

    private fun dead(message: Any, why: DeadLetter.Why) = scope.dead(DeadLetter(address, message, why))

    private fun turned(message: M) {
        val next = try {
            supervised { behaviour.turn(this, ctx, state, message) }
        } catch (_: WrongGuess) {
            return guessedWrong(message)
        } ?: return
        if (next === Next.Unhandled) dead(message, DeadLetter.Why.Unhandled)
        run.updateAndGet { after(it, next, message) }
        settled(next)
    }

    private fun start(key: Any, delay: Duration, message: Any, every: Duration?) {
        cancel(key)
        val timer = scope.schedule(this, key, delay, message, every)
        run.updateAndGet { it.copy(timers = it.timers + (key to timer)) }
        scope.start(timer)
    }

    private fun cancel(key: Any) {
        run.getAndUpdate { it.copy(timers = it.timers - key) }.timers[key]?.let(scope::unschedule)
    }

    /**
     * Only the timer still running under its key is handled: one cancelled or replaced since is dropped. Unless the
     * turn cancelled or replaced it, a periodic one then starts again from now, and any other is done.
     */
    private fun timed(timer: TestTimer) {
        if (run.get().timers[timer.key] !== timer) return
        @Suppress("UNCHECKED_CAST")
        turned(timer.message as M)
        if (run.get().timers[timer.key] === timer) {
            val every = timer.every
            if (every == null) {
                run.updateAndGet { it.copy(timers = it.timers - timer.key) }
            } else {
                start(timer.key, every, timer.message, every)
            }
        }
        if (timer.key !== ReceiveTimeoutKey) heard()
    }

    /**
     * A start that guessed the entity was new, shown wrong by its first command: restarts as a failure would, without
     * asking supervision or counting a restart, and puts [message] back to be handled first once the start has run.
     */
    private fun guessedWrong(message: M) {
        stopChildren()
        letGo()
        run.updateAndGet { it.copy(state = behaviour.initial) }
        restarted.set(true)
        starting.set(behaviour.start != null)
        stashed.set(
            Stash(stashCapacity, address.path).apply {
                keep(message)
                unstashAll()
            },
        )
    }

    /** Handles every message put back from the stash, before anything else on the queue reaches this actor. */
    private tailrec fun replayed() {
        if (stopped) return
        if (starting.get()) started()
        val message = stashed.get()?.next() ?: return
        @Suppress("UNCHECKED_CAST")
        turned(message as M)
        heard()
        replayed()
    }

    /** The turn returned [next]: timers it asked for are the new state's if it is theirs, and cancelled if not. */
    private fun settled(next: Next<S>) {
        val before = run.getAndUpdate { it.copy(asked = null) }
        val asking = before.asked
        val becoming = (next as? Next.Become<S>)?.state
        val current = before.scoped
        when {
            next !is Next.Become -> asking?.let(::drop)

            asking != null && asking.state === becoming -> {
                current?.let(::drop)
                run.updateAndGet { it.copy(scoped = asking) }
            }

            else -> {
                asking?.let(::drop)
                if (current != null && current.endsAt(becoming)) {
                    drop(current)
                    run.updateAndGet { it.copy(scoped = null) }
                }
            }
        }
    }

    private fun drop(timers: TimersOfState) = timers.keys.forEach(::cancel)

    /** A message was handled, so the silence the receive timeout waits for starts again. */
    private fun heard() {
        run.get().silence?.let { start(ReceiveTimeoutKey, it.after, it.message, null) }
    }

    // Whether the behaviour's start is to run again, after a restart.
    private val starting = AtomicBoolean(false)

    private fun started() {
        starting.set(false)
        val start = behaviour.start ?: return
        val next = supervised { start(this, ctx, state) } ?: return
        run.updateAndGet { after(it, next, null) }
        settled(next)
    }

    /**
     * Restarts the actor as a failure its schedule restarts would, whatever the schedule: its children stop, its
     * timers, stash and registrations go, its state is the initial one again, and its start runs again.
     */
    fun restart() {
        check(!stopped) { "${address.path} has stopped, so it cannot be restarted" }
        stopChildren()
        letGo()
        run.updateAndGet { it.copy(state = behaviour.initial) }
        restarted.set(true)
        started()
        if (stopped) ended()
    }

    // Crashed, so that its next message starts it again first.
    private val down = AtomicBoolean(false)

    internal fun crash() {
        stopChildren()
        letGo()
        starting.set(false)
        restarted.set(false)
        run.updateAndGet { it.copy(state = behaviour.initial) }
        down.set(true)
    }

    /** Cancels every timer and drops the stash: nothing of either outlives a restart or a stop. */
    private fun letGo() {
        scope.receptionist.forget(this)
        stashed.set(null)
        run.getAndUpdate {
            it.copy(timers = emptyMap(), silence = null, scoped = null, asked = null)
        }.timers.values.forEach(scope::unschedule)
    }

    private fun signalled(signal: Signal) {
        run.updateAndGet { it.copy(signals = it.signals + signal) }
        val handler = behaviour.signal ?: return
        val next = supervised { handler(this, ctx, state, signal) } ?: return
        run.updateAndGet { after(it, next, null) }
        settled(next)
    }

    /**
     * A turn or a handler, with its raise and throw handed to the schedule; null where a throw restarted the actor.
     * With no schedule a throw reaches the test.
     */
    @Suppress("TooGenericExceptionCaught")
    private inline fun supervised(body: Fail<E>.() -> Next<S>): Next<S>? =
        try {
            boundary.guarded(body) { error -> failed(Failure.Declared(error)) }
        } catch (wrong: WrongGuess) {
            throw wrong
        } catch (thrown: Throwable) {
            failed(Failure.Thrown(thrown.nonFatalOrThrow()))
            if (run.get().supervision == null) throw thrown
            if (stopped) Next.Stop else null
        }

    /** Stops this actor from outside: its children first, then its own Stopping. */
    internal fun halt() {
        if (run.getAndUpdate { it.copy(stopped = true) }.stopped) return
        ended()
    }

    private fun stopChildren() {
        run.getAndUpdate { it.copy(children = emptyList()) }.children.forEach { it.halt() }
    }

    private fun forget(child: TestActor<*, *, *>) {
        run.updateAndGet { it.copy(children = it.children - child) }
    }

    internal fun watchedBy(watcher: TestActor<*, *, *>) {
        if (run.get().ended) {
            scope.post(watcher, TestSignalled(Signal.Terminated(this)))
        } else {
            run.updateAndGet { it.copy(watchers = it.watchers + watcher) }
        }
    }

    // The actor is ending: a raise or a throw from its Stopping handler has no schedule left to go to.
    @Suppress("TooGenericExceptionCaught", "SwallowedException")
    private fun ended() {
        if (run.getAndUpdate { it.copy(ended = true) }.ended) return
        stopChildren()
        stashed.get()?.drain().orEmpty().forEach { dead(it, DeadLetter.Why.Stopped) }
        letGo()
        run.updateAndGet { it.copy(signals = it.signals + Signal.Stopping) }
        behaviour.signal?.let { handler ->
            try {
                boundary.guarded({ handler(this, ctx, state, Signal.Stopping) }) { Next.Stop }
            } catch (thrown: Throwable) {
                thrown.nonFatalOrThrow()
            }
        }
        parent?.forget(this)
        run.get().watchers.forEach { scope.post(it, TestSignalled(Signal.Terminated(this))) }
    }

    /** Restarts from the initial state after the schedule's delay, or stops when there is no schedule or it is done. */
    private fun failed(failure: Failure<E>): Next<S> =
        when (val decision = run.get().supervision?.invoke(failure)) {
            is Schedule.Decision.Continue -> {
                stopChildren()
                letGo()
                clock.sleep(decision.delay)
                run.updateAndGet {
                    it.copy(state = behaviour.initial, supervision = decision.step, delays = it.delays + decision.delay)
                }
                restarted.set(true)
                starting.set(behaviour.start != null)
                Next.Stay
            }

            else -> {
                run.updateAndGet { it.copy(failure = failure, stopped = true) }
                Next.Stop
            }
        }

    private fun after(run: Run<M, S, E>, next: Next<S>, message: M?): Run<M, S, E> = when (next) {
        Next.Stay -> run
        is Next.Become -> run.copy(state = next.state)
        Next.Stop -> run.copy(stopped = true)
        Next.Unhandled -> if (message == null) run else run.copy(unhandled = run.unhandled + message)
    }
}

/** A timer in the test's own time: also what goes on the queue once it falls due. */
internal class TestTimer(
    val at: Duration,
    val seq: Long,
    val to: TestActor<*, *, *>,
    val key: Any,
    val message: Any,
    val every: Duration? = null,
)

/** The key of a message held back by [Faults.delay], which keeps who told it. */
private class InFlight(val from: Address)

/** A signal on its way through the test's queue. */
private class TestSignalled(val signal: Signal)

/** The start of a new test actor, on the queue so that it runs to idle like a message. */
private object TestStarted

private class TestReply<A : Any>(override val address: Address) : Reply<A> {
    val answer = AtomicReference<A>()

    override fun invoke(answer: A) {
        check(this.answer.compareAndSet(null, answer)) { "a second reply, $answer, after ${this.answer.get()}" }
    }
}
