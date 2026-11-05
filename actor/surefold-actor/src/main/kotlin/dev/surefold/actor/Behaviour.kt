package dev.surefold.actor

import dev.surefold.Carriers
import dev.surefold.Schedule
import dev.surefold.fail.Fail
import java.util.Objects
import kotlin.time.Duration

/** Where an actor lives. A ref is named by one, so that a ref can later name an actor on another node. */
data class Address(val node: String, val path: String, val incarnation: Long)

interface ActorRef<in M : Any> {
    val address: Address

    fun tell(message: M)
}

/** One answer, once. A narrow ref rather than a closure, so that an ask can later cross a node. */
interface Reply<in A : Any> {
    val address: Address

    operator fun invoke(answer: A)
}

interface Ctx<M : Any> {
    val self: ActorRef<M>

    /** Messages this actor sends itself later, each under a key; a stop or a restart cancels them all. */
    val timers: Timers<M>

    /** Where this actor's scope keeps events; it fails when the scope has been given none. */
    val journal: Journal

    /** Where this actor's scope keeps snapshots, or null when it has been given none and none are taken. */
    val snapshots: SnapshotStore?
        get() = null

    /**
     * Tells this actor [message] once nothing has arrived for [after], and again only after the next message: once per
     * silence. Any message resets it, a timer's included; a restart turns it off.
     */
    fun receiveTimeout(after: Duration, message: M)

    /** Turns the receive timeout off: `receiveTimeout(null)`. */
    fun receiveTimeout(off: Nothing?)

    /**
     * [state], with [timers] that belong to it: they end when the actor next becomes a state of another class, and a
     * copy of this one keeps them. Becoming a state with timers again replaces the ones it had. Only a turn that
     * returns this becomes it; timers from one it does not return never start.
     */
    fun <S> become(state: S, timers: StateTimers<M>.() -> Unit): Next<S>

    /**
     * Keeps [message] to handle later, when [unstashAll] puts it back. The stash is bounded, and keeping one more than
     * it holds fails the turn. A restart or a stop drops it.
     */
    fun stash(message: M)

    /** Puts every kept message back, to be handled before anything in the mailbox, in the order kept. */
    fun unstashAll()

    /** Lists this actor under [key] until it stops or restarts, for [subscribe] and `find` to see. */
    fun register(key: ServiceKey<M>)

    /**
     * Tells this actor [listing] of the actors under [key] now, and again whenever it changes, as a message of its own
     * type, until it stops or restarts.
     */
    fun <K : Any> subscribe(key: ServiceKey<K>, listing: (Set<ActorRef<K>>) -> M)

    /** Hears [Signal.Terminated] once [ref] stops, once however often it is asked; at once if it already has. */
    fun watch(ref: ActorRef<*>)

    /**
     * Stops [child], one of this actor's own, as a stop from outside would: at once when it is idle, after its running
     * turn otherwise. Watch it to hear when it has.
     */
    fun stop(child: ActorRef<*>)

    /**
     * A child: named under this actor, and stopped before it, whether this actor stops, fails or is restarted. A
     * restarted parent has none until its turn spawns them again.
     */
    fun <C : Any, T, F> spawn(
        name: String,
        behaviour: Behaviour<C, T, F>,
        restart: Schedule<Failure<F>, *>? = null,
    ): ActorRef<C>
}

/**
 * An actor's own timers. A timer's message arrives through the mailbox and is handled by the turn like any other.
 * Starting a key that is running replaces it, and a message from a cancelled or replaced timer never arrives, even one
 * already in the mailbox.
 */
interface Timers<in M : Any> {
    /** Tells this actor [message] once [delay] has passed on its scope's clock; at once when it is not positive. */
    fun after(key: Any, delay: Duration, message: M)

    /**
     * Tells this actor [message] every [interval] until [key] is cancelled or started again. Each is measured from when
     * the last was handled, so an actor that falls behind is never sent a burst to catch up.
     */
    fun every(key: Any, interval: Duration, message: M)

    /** Cancels the timer under [key], if one is running. */
    fun cancel(key: Any)
}

/** The timers of one state, started by `ctx.become(state) { … }`; they have no key, since the state is theirs. */
interface StateTimers<in M : Any> {
    fun after(delay: Duration, message: M)

    fun every(interval: Duration, message: M)
}

/** What happens to an actor rather than what is sent to it, handled beside its messages with no `else`. */
sealed interface Signal {
    /** The actor is ending, by `stop()`, a failure with nothing left to restart it, or its scope closing. */
    data object Stopping : Signal

    /** An actor this one watches has stopped. */
    data class Terminated(val ref: ActorRef<*>) : Signal
}

sealed interface Next<out S> {
    data object Stay : Next<Nothing>

    data class Become<out S>(val state: S) : Next<S>

    data object Stop : Next<Nothing>

    /** The state stays, and the message goes where unhandled messages go. */
    data object Unhandled : Next<Nothing>
}

fun stay(): Next<Nothing> = Next.Stay

fun <S> become(state: S): Next<S> = Next.Become(state)

fun stop(): Next<Nothing> = Next.Stop

fun unhandled(): Next<Nothing> = Next.Unhandled

/**
 * An actor described: where it starts, and what one message does. Nothing runs until something runs it. A turn leaves
 * with a declared failure by raising [E]; one that never does is `Behaviour<M, S, Nothing>`.
 */
class Behaviour<M : Any, S, out E>
internal constructor(
    val initial: S,
    /** What one message does: the actor's turn. */
    val turn: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
    /** How it takes a [Signal]; with none, a signal changes nothing. */
    val signal: (Fail<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>)?,
    /** What it does before its first message, and again after each restart: a turn with no message. */
    val start: (Fail<E>.(ctx: Ctx<M>, state: S) -> Next<S>)?,
    /**
     * A turn over a run of the plain messages already waiting, up to [batch] of them (spec 0086). With none, or a batch
     * of 1, each message has a [turn] of its own. Signals, timers and replayed messages always do.
     */
    internal val turns: (Fail<E>.(ctx: Ctx<M>, state: S, messages: Run<M>) -> Batched<S>)?,
    internal val batch: Int,
) {
    constructor(
        initial: S,
        turn: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
        signal: (Fail<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>)? = null,
        start: (Fail<E>.(ctx: Ctx<M>, state: S) -> Next<S>)? = null,
    ) : this(initial, turn, signal, start, null, 1)

    /** The [turn], under the name it had before spec 0222. */
    @Deprecated("Named turn, as the actors guide calls it", ReplaceWith("turn"))
    val step: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>
        get() = turn
}

/**
 * What a run of messages came to: the [next] of the whole run, the messages it left [unhandled], which go where an
 * unhandled message goes, and those it never ran because it stopped first, which go where a stopped actor's do.
 */
internal class Batched<out S>(val next: Next<S>, val unhandled: List<Any>, val unrun: List<Any>)

/**
 * A run of messages handled by one turn (spec 0223), in the order they came. Each carries what its sender had bound
 * (spec 0122), and the turn runs inside none of it: [within] is where a message's own context is.
 */
interface Run<out M : Any> : List<M> {
    /** Runs [block] inside what the message at [index] carried from its sender, and nobody else's. */
    fun <A> within(index: Int, block: () -> A): A
}

/** A run of [messages], each with what it [carried]; with none carried, [within] binds nothing. */
internal class Carrying<out M : Any>(
    private val messages: List<M>,
    private val carried: List<Map<String, String>>?,
) : AbstractList<M>(), Run<M> {
    override val size: Int
        get() = messages.size

    override fun get(index: Int): M = messages[index]

    override fun <A> within(index: Int, block: () -> A): A {
        val bound = carried?.get(Objects.checkIndex(index, size)).orEmpty()
        return if (bound.isEmpty()) block() else Carriers.within(bound, block)
    }
}

/**
 * A behaviour whose [turns] takes the plain messages already waiting, up to [batch] of them, as one [Run] (spec 0223).
 * A message handled alone, such as a timer's, a stashed one put back, or any with a batch of 1, is a run of one, inside
 * its own sender's context. A run answered `unhandled()` sends every message of it to dead letters. Name all three
 * types, `batching<M, S, E>(…)`: left to inference, a call resolves to the overload that never raises.
 */
@JvmName("raisingBatching")
fun <M : Any, S, E> batching(
    initial: S,
    batch: Int,
    turns: Fail<E>.(ctx: Ctx<M>, state: S, messages: Run<M>) -> Next<S>,
): Behaviour<M, S, E> {
    require(batch > 0) { "a batch of $batch messages takes none" }
    return Behaviour(
        initial = initial,
        turn = { ctx, state, message -> turns(ctx, state, Carrying(listOf(message), null)) },
        signal = null,
        start = null,
        turns = { ctx, state, run ->
            val next = turns(ctx, state, run)
            Batched(next, if (next === Next.Unhandled) run else emptyList(), emptyList())
        },
        batch = batch,
    )
}

/** A batching behaviour that never raises, so nothing has to name a failure type it does not have. */
fun <M : Any, S> batching(
    initial: S,
    batch: Int,
    turns: Fail<Nothing>.(ctx: Ctx<M>, state: S, messages: Run<M>) -> Next<S>,
): Behaviour<M, S, Nothing> = batching<M, S, Nothing>(initial, batch, turns)

/** This behaviour, taking signals with [handler]. */
fun <M : Any, S, E> Behaviour<M, S, E>.onSignal(
    handler: Fail<E>.(ctx: Ctx<M>, state: S, signal: Signal) -> Next<S>,
): Behaviour<M, S, E> = Behaviour(initial, turn, handler, start, turns, batch)

/**
 * This behaviour, running [handler] before its first message and again after each restart, since a restart loses what
 * the actor had: its registrations, its subscriptions and its timers. A throw from it fails the actor as a turn's
 * would. It runs after any start the behaviour already has, such as a persistent one's recovery.
 */
fun <M : Any, S, E> Behaviour<M, S, E>.onStart(handler: (ctx: Ctx<M>) -> Unit): Behaviour<M, S, E> {
    val before = start
    return Behaviour(
        initial,
        turn,
        signal,
        { ctx, state ->
            val next = before?.invoke(this, ctx, state) ?: Next.Stay
            handler(ctx)
            next
        },
        turns,
        batch,
    )
}

/**
 * A behaviour whose turn may raise [E]. Name all three types, `behaviour<M, S, E>(…)`: left to inference, a call
 * resolves to the overload that never raises.
 */
@JvmName("raising")
fun <M : Any, S, E> behaviour(
    initial: S,
    turn: Fail<E>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
): Behaviour<M, S, E> = Behaviour(initial, turn)

/** A behaviour that never raises, so nothing has to name a failure type it does not have. */
fun <M : Any, S> behaviour(
    initial: S,
    turn: Fail<Nothing>.(ctx: Ctx<M>, state: S, message: M) -> Next<S>,
): Behaviour<M, S, Nothing> = Behaviour(initial, turn)

/** Why an actor stopped other than by answering `stop()`: a failure it declared, or a throw nobody declared. */
sealed interface Failure<out E> {
    data class Declared<out E>(val error: E) : Failure<E>

    data class Thrown(val throwable: Throwable) : Failure<Nothing>
}

/**
 * The one boundary a turn's `fail` unwinds to. Each actor has its own, and a failure carries it, so that a failure
 * belonging to an `attempt` inside the turn is that `attempt`'s and never mistaken for the actor's.
 */
internal class StepFail<E> : Fail<E> {
    override fun fail(error: E): Nothing = throw Raised(error, this)

    /** What [step] returned, or [raised] of what it failed with here. Inline, so a step allocates nothing. */
    inline fun <A> guarded(step: Fail<E>.() -> A, raised: (E) -> A): A =
        try {
            step(this)
        } catch (failure: Raised) {
            if (failure.by !== this) throw failure
            @Suppress("UNCHECKED_CAST") raised(failure.error as E)
        }
}

/** Carries a failure to its [StepFail]; no stack trace, since it is control flow, not a fault. */
internal class Raised(val error: Any?, val by: StepFail<*>) : RuntimeException(null, null, false, false)

/**
 * A protocol by name, which actors register under and are found by, as refs of that protocol. One id names one
 * protocol: two keys with the same id are the same key.
 */
data class ServiceKey<M : Any>(val id: String)

/** A message nobody handled: told to [recipient] after it stopped, or answered `unhandled()` by its turn. */
data class DeadLetter(val recipient: Address, val message: Any, val why: Why) {
    enum class Why {
        /** The actor had stopped, or stopped with this still in its mailbox or its stash. */
        Stopped,

        /** The actor's turn answered `unhandled()`. */
        Unhandled,

        /** The node it was for could not be reached, or had no room left to send it: spec 0068. */
        Unreachable,

        /** It reached its node, and no actor there was the one it was for. */
        NoSuchActor,

        /**
         * The actor was too busy to take it: surefold's own plumbing, a region, an entity manager or a topic, had
         * already kept as many for it as it may while its mailbox was full (spec 0095).
         */
        Full,
    }
}

/**
 * A ref that is not an actor on threads, and can still be watched: an actor on another node, say. It calls [notify]
 * once, when what it names has ended or can no longer be reached, and at once if that is already so.
 */
interface Watchable {
    fun onTerminated(notify: () -> Unit)
}

sealed interface AskFailure {
    data object TimedOut : AskFailure

    data object Stopped : AskFailure

    /**
     * The message could not be sent to the node the actor is on (spec 0103): its connection is down or its queue full,
     * so no answer will come. Never the answer from an actor in this process.
     */
    data object Unreachable : AskFailure
}

/**
 * A reply an ask in this process waits on, which whatever carries the asking message can end unanswered (spec 0103): a
 * transport that could not send the message ends the ask with [AskFailure.Unreachable] rather than letting it wait out
 * its time. An ending after the answer, or a second one, is ignored.
 */
interface Unanswerable {
    fun unanswered(why: AskFailure)
}
