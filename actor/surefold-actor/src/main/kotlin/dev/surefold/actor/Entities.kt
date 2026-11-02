package dev.surefold.actor

import dev.surefold.Carriers
import dev.surefold.Schedule
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** What an entities manager is told: a message for one entity, or its own bookkeeping. */
sealed interface Entities<M : Any>

/** [message] for the entity [id]. */
internal class Deliver<M : Any>(val id: String, val message: M) : Entities<M>

/** The entity [id] has had nothing for its `passivateAfter`. */
internal class Passivate<M : Any>(val id: String) : Entities<M>

/** Time to hand busy entities what was kept for them. */
internal class Drain<M : Any> : Entities<M>

/** The key of the timer that passivates [id]. */
private data class IdleKey(val id: String)

/** The key of the timer that hands busy entities what was kept for them. */
private object DrainKey

/**
 * How many messages surefold's own plumbing keeps for one busy receiver, an entity, a shard or a subscriber, before the
 * next is a dead letter for being [DeadLetter.Why.Full] (spec 0095).
 */
internal const val KEEP_AT_MOST = 10_000

/** How soon kept messages are offered to a busy receiver again. */
private val DRAIN_AFTER = 10.milliseconds

/**
 * What surefold's own plumbing tells its scope through a ctx: a dead letter it found itself, and how many messages it
 * keeps for busy receivers, which `awaitIdle` counts as not yet handled (spec 0095).
 */
internal interface DeadLetters {
    fun deadLetter(letter: DeadLetter)

    fun kept(delta: Int)
}

internal fun Ctx<*>.deadLetter(letter: DeadLetter) {
    (this as? DeadLetters)?.deadLetter(letter)
}

internal fun Ctx<*>.kept(delta: Int) {
    if (delta != 0) (this as? DeadLetters)?.kept(delta)
}

/**
 * One manager's bookkeeping: the entity running for each id, the id of each running entity, and what has arrived for
 * each id whose entity is stopping. Made on the manager's first message, so a restart, and a second spawn of the same
 * behaviour, start with none.
 */
private class EntityBook<M : Any, S, E>(
    private val passivateAfter: Duration,
    private val restart: Schedule<Failure<E>, *>?,
    private val onRunning: (delta: Int) -> Unit,
    private val entity: (id: String) -> Behaviour<M, S, E>,
) {
    private val running = HashMap<String, ActorRef<M>>()
    private val ids = HashMap<ActorRef<*>, String>()

    // Each kept with what it carried, so it is handled in its sender's trace once the entity starts again (spec 0122).
    private val stopping = HashMap<String, MutableList<Pair<M, Map<String, String>>>>()

    // What an entity's full mailbox could not take yet, in order: everything after it waits behind it (spec 0095).
    @OptIn(PlumbingSeam::class)
    private val handOn = HandOn<String, M>()

    /** Tells the entity [id] [message], starting it if it is not running, and keeping it if it is stopping. */
    fun deliver(ctx: Ctx<Entities<M>>, id: String, message: M) {
        val held = stopping[id]
        if (held != null) {
            held += message to Carriers.capture()
            return
        }
        // The idle timer is armed before the entity has the message, so an entity that is busy with it can already
        // be passivated: a clock moved on once the entity has begun sees a timer to fire.
        ctx.timers.after(IdleKey(id), passivateAfter, Passivate(id))
        val ref = running[id] ?: start(ctx, id)
        @OptIn(PlumbingSeam::class)
        if (handOn.tell(ctx, id, ref, message)) ctx.timers.after(DrainKey, DRAIN_AFTER, Drain())
    }

    /** Offers each busy entity what was kept for it, in order, as far as it has room, and comes back for the rest. */
    @OptIn(PlumbingSeam::class)
    fun drain(ctx: Ctx<Entities<M>>) {
        if (handOn.drain(ctx)) ctx.timers.after(DrainKey, DRAIN_AFTER, Drain())
    }

    fun passivate(ctx: Ctx<Entities<M>>, id: String) {
        // An entity with messages still kept for it is busy, not idle.
        @OptIn(PlumbingSeam::class)
        if (id in handOn) return ctx.timers.after(IdleKey(id), passivateAfter, Passivate(id))
        val ref = running.remove(id) ?: return
        stopping[id] = mutableListOf()
        ctx.stop(ref)
    }

    /** An entity has stopped, by passivation or by itself; what arrived for it meanwhile starts it again. */
    fun ended(ctx: Ctx<Entities<M>>, ref: ActorRef<*>) {
        val id = ids.remove(ref) ?: return
        onRunning(-1)
        if (running[id] == ref) {
            running.remove(id)
            ctx.timers.cancel(IdleKey(id))
        }
        // What was kept for an entity that stopped by itself goes to the next one, before anything newer.
        @OptIn(PlumbingSeam::class)
        handOn.take(ctx, id) { deliver(ctx, id, it) }
        stopping.remove(id).orEmpty().forEach { (message, carried) ->
            Carriers.within(carried) { deliver(ctx, id, message) }
        }
    }

    private fun start(ctx: Ctx<Entities<M>>, id: String): ActorRef<M> {
        val ref = ctx.spawn(id, entity(id), restart)
        ctx.watch(ref)
        running[id] = ref
        ids[ref] = id
        onRunning(1)
        return ref
    }

    /** The manager is stopping, and its entities with it: none of them is running from now on. */
    @OptIn(PlumbingSeam::class)
    fun stopping(ctx: Ctx<Entities<M>>) {
        if (ids.isNotEmpty()) onRunning(-ids.size)
        ids.clear()
        handOn.drop(ctx, DeadLetter.Why.Stopped)
    }
}

/**
 * A manager of entities: one actor per id, spawned by [entity] as the manager's child on the id's first message, and
 * stopped once it has had nothing for [passivateAfter]. A message for an entity that is stopping is kept, and starts
 * it again once it has stopped. Talk to one through [entity], whose ref stays good while the entity comes and goes.
 * [restart] applies to each entity on its own. [onRunning] hears each entity start (+1) and end (-1), and every one
 * still running when the manager stops, for a count of what runs (spec 0081); it runs on the manager's turn. A manager
 * restarted by its own supervision stops its entities without hearing of it, so it keeps a count only when it is
 * spawned without a restart, as sharding spawns it.
 */
fun <M : Any, S, E> entities(
    passivateAfter: Duration,
    restart: Schedule<Failure<E>, *>? = null,
    onRunning: (delta: Int) -> Unit = {},
    entity: (id: String) -> Behaviour<M, S, E>,
): Behaviour<Entities<M>, Any?, Nothing> {
    require(passivateAfter.isPositive()) { "passivateAfter must be positive, was $passivateAfter" }
    return behaviour<Entities<M>, Any?>(null) { ctx, state, message ->
        @Suppress("UNCHECKED_CAST")
        val book = state as EntityBook<M, S, E>? ?: EntityBook(passivateAfter, restart, onRunning, entity)
        when (message) {
            is Deliver -> book.deliver(ctx, message.id, message.message)
            is Passivate -> book.passivate(ctx, message.id)
            is Drain -> book.drain(ctx)
        }
        if (state == null) become(book) else stay()
    }.onSignal { ctx, state, signal ->
        @Suppress("UNCHECKED_CAST")
        val book = state as EntityBook<M, S, E>?
        if (signal is Signal.Terminated && book != null) book.ended(ctx, signal.ref)
        if (signal == Signal.Stopping) book?.stopping(ctx)
        stay()
    }
}

/** The entity [id] of these entities, as a ref of its own protocol: good whether or not it is running now. */
fun <M : Any> ActorRef<Entities<M>>.entity(id: String): ActorRef<M> = EntityRef(this, id)

internal class EntityRef<M : Any>(val manager: ActorRef<Entities<M>>, val id: String) : ActorRef<M> {
    override val address = Address(manager.address.node, "${manager.address.path}/$id", manager.address.incarnation)

    override fun tell(message: M) = manager.tell(Deliver(id, message))

    override fun equals(other: Any?): Boolean = other is EntityRef<*> && other.manager == manager && other.id == id

    override fun hashCode(): Int = manager.hashCode() * 31 + id.hashCode()

    override fun toString(): String = "EntityRef(${address.path})"
}
