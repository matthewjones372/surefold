package io.github.matthewjones372.surefold.actor

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Scope
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.increment
import dev.surefold.logWarn
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Why a producer's `send` did not keep a command (spec 0098). */
sealed interface NotSent

/** A producer already keeps as many unconfirmed commands as it may, and none was confirmed in time (spec 0079). */
data object Full : NotSent

/** A durable producer's journal threw, so the command was not kept (spec 0098). */
data class Unwritten(val cause: Throwable) : NotSent

/**
 * Sends commands at least once (spec 0079). Each command to an entity is numbered and kept until the entity confirms
 * it, and sent again every `resendAfter` until then, so a move or a passivation that loses a copy makes it late, not
 * lost. An entity has one command in flight at a time: the next is sent once the one before it is confirmed, so they
 * arrive in the order they were sent, however many copies are lost.
 */
class Producer<M : Any> internal constructor(
    private val room: Semaphore,
    private val keep: Int,
    private val within: Duration,
    private val meters: ProducerMeters,
    private val keeping: Keeping<M>,
    private val recovered: CountDownLatch? = null,
    private val down: AtomicReference<Throwable?>? = null,
) {
    /**
     * Keeps the command [command] builds for entity [to] and returns, or waits up to `within` while the producer
     * keeps as many as it may, and then answers [Full]. [command] runs once, on the caller's thread, so what it
     * throws reaches the caller and nothing is kept. A durable producer encodes the command there too, and so throws
     * for one its codec refuses; it returns once the command is written to its journal, and answers [Full] too if
     * that takes longer than `within`, when such a command may still be written, and then sent (spec 0085). It
     * answers [Unwritten] when its journal throws, and at once while it is down, until a restart reads the journal
     * again (spec 0098).
     */
    fun send(to: String, command: (Delivery) -> M): Either<NotSent, Unit> {
        down?.get()?.let { cause -> return unwritten(cause) }
        if (!room.tryAcquire(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return full()
        var kept: Handed? = null
        try {
            kept = keeping.keep(to, command)
        } finally {
            // A command that failed to build, or found the producer's own mailbox full, holds no room.
            if (kept == null) room.release()
        }
        return when (val answer = kept?.answer(within)) {
            null -> full()
            else -> answer.fold({ unwritten(it) }, { Unit.right() })
        }
    }

    private fun full(): Either<NotSent, Unit> {
        meters.full.increment()
        return Full.left()
    }

    private fun unwritten(cause: Throwable): Either<NotSent, Unit> {
        meters.unwritten.increment()
        return Unwritten(cause).left()
    }

    /**
     * Whether every command kept was confirmed within [within]: for a service about to stop, or a test that waits for
     * its commands to land. A `send` while it waits may make it wait longer.
     */
    fun drain(within: Duration): Boolean {
        // A durable producer's recovered commands hold room only once its actor has claimed it.
        if (recovered?.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS) == false) return false
        if (!room.tryAcquire(keep, within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) return false
        room.release(keep)
        return true
    }
}

/**
 * How a [Producer] hands a command to its actor, on the caller's thread: what `send` waits on, or null when the
 * actor's mailbox is full.
 */
internal fun interface Keeping<M : Any> {
    fun keep(to: String, command: (Delivery) -> M): Handed?
}

/** A command handed to a producer's actor, as `send` waits on it. */
internal fun interface Handed {
    /** Right once kept, the journal's throw once refused, or null when neither came within [within]. */
    fun answer(within: Duration): Either<Throwable, Unit>?
}

/** Kept already: an in-memory producer's command is kept once its actor has it. */
private val HANDED = Handed { Unit.right() }

/**
 * [message] told, or false when this is an actor's thread and the mailbox it reaches is full: a tell from an actor
 * never waits for room, and throws instead.
 */
@Suppress("SwallowedException")
internal fun <M : Any> ActorRef<M>.handed(message: M): Boolean = try {
    tell(message)
    true
} catch (_: IllegalStateException) {
    false
}

/**
 * An in-memory producer's numbering, on the caller's thread: each command is built there, once, with its number, and
 * handed to [actor] under one lock, so the actor has each entity's commands in the order they were numbered.
 */
private class Numbering<M : Any>(private val producer: String, private val actor: ActorRef<Any>) : Keeping<M> {
    private val lock = ReentrantLock()
    private val next = HashMap<String, Long>()

    override fun keep(to: String, command: (Delivery) -> M): Handed? = lock.withLock {
        val sequence = next[to] ?: 1

        @Suppress("UNCHECKED_CAST")
        val built = command(Delivery(producer, to, sequence, actor as ActorRef<Confirmed>))
        if (!actor.handed(KeepCommand(to, KeptCommand(sequence, built)))) return null
        next[to] = sequence + 1
        HANDED
    }
}

/**
 * A producer in this scope that sends to the entity [route] names. Its deliveries carry [id] and an incarnation of
 * its own, so a producer started again under the same [id] is not taken for a duplicate of the one before. It keeps
 * at most [keep] unconfirmed commands, and a `send` waits up to [within] for room. When the scope closes, it waits
 * up to [drainWithin] for what it keeps to be confirmed before its actor stops, so a service that stops does not lose
 * what it has sent (spec 0080); a [drainWithin] of zero loses it, as a crash does.
 */
fun <F, M : Any> Scope<F>.producer(
    id: String,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    drainWithin: Duration = 30.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = meters(id)
    val producer = incarnation(id)
    val actor = spawn("producer-$id", producing<M>(resendAfter, room, keep, meters, route))
    return Producer(room, keep, within, meters, Numbering<M>(producer, actor)).drainedOnClose(this, id, drainWithin)
}

/** This scope's instruments for the producer [id]. */
internal fun Scope<*>.meters(id: String) = ProducerMeters(
    unconfirmed = gauge("surefold.delivery.unconfirmed", "producer" to id),
    resent = counter("surefold.delivery.resent", "producer" to id),
    full = counter("surefold.delivery.full", "producer" to id),
    unwritten = counter("surefold.delivery.unwritten", "producer" to id),
)

/** This producer, waiting up to [drainWithin] for what it keeps when [scope] closes (spec 0080). */
internal fun <M : Any> Producer<M>.drainedOnClose(scope: Scope<*>, id: String, drainWithin: Duration): Producer<M> {
    if (drainWithin.isPositive()) {
        scope.onClose {
            val drained = drain(drainWithin)
            if (!drained) logWarn("producer $id closed with commands unconfirmed after $drainWithin")
        }
    }
    return this
}

/** A producer among these test actors, as [Scope.producer]; its resends wait on [TestActors.advance]. */
fun <M : Any> TestActors.producer(
    id: String,
    resendAfter: Duration = 2.seconds,
    keep: Int = 1_000,
    within: Duration = 5.seconds,
    route: (to: String) -> ActorRef<M>,
): Producer<M> {
    val room = room(keep)
    val meters = ProducerMeters(Gauge { }, Counter { }, Counter { }, Counter { })
    val actor = spawn("producer-$id", producing<M>(resendAfter, room, keep, meters, route))
    return Producer(room, keep, within, meters, Numbering<M>(incarnation(id), actor))
}

internal fun room(keep: Int): Semaphore {
    require(keep > 0) { "a producer that keeps $keep commands can send none" }
    return Semaphore(keep)
}

private fun incarnation(id: String) = "$id/${UUID.randomUUID()}"

/** A command to keep, built and numbered by [Producer.send]. */
private class KeepCommand<M : Any>(val to: String, val kept: KeptCommand<M>)

/** A command to keep in the journal, encoded by a durable [Producer.send], which waits for its [answer]. */
internal class KeepDurably(val to: String, val bytes: ByteArray) : Handed {
    private val answered = CountDownLatch(1)
    private val refused = AtomicReference<Throwable?>()

    val isAnswered: Boolean get() = answered.count == 0L

    fun written() = answered.countDown()

    fun unwritten(cause: Throwable) {
        refused.set(cause)
        answered.countDown()
    }

    override fun answer(within: Duration): Either<Throwable, Unit>? =
        if (answered.await(within.inWholeNanoseconds, TimeUnit.NANOSECONDS)) refused.get()?.left() ?: Unit.right()
        else null
}

/** Time to send every entity's unconfirmed command again. */
internal data object ResendUnconfirmed

/** A producer's instruments (spec 0081): what it keeps unconfirmed, what it sent again, each `Full` and `Unwritten`. */
internal class ProducerMeters(val unconfirmed: Gauge, val resent: Counter, val full: Counter, val unwritten: Counter)

/** The producer's actor: keeps commands, sends each entity its first, and frees room as they confirm. */
private fun <M : Any> producing(
    resendAfter: Duration,
    room: Semaphore,
    keep: Int,
    meters: ProducerMeters,
    route: (String) -> ActorRef<M>,
): Behaviour<Any, Outboxes<M>, Nothing> {
    // A copy that finds the entity's mailbox full is not lost: it is sent again on the next resend.
    fun perform(send: Send<M>?) = send?.let { route(it.to).handed(it.command) }

    // Room taken is what `send` has kept and no entity has confirmed yet.
    fun measure() = meters.unconfirmed.set((keep - room.availablePermits()).toDouble())
    return behaviour<Any, Outboxes<M>>(Outboxes()) { _, outboxes, message ->
        when (message) {
            is KeepCommand<*> -> {
                @Suppress("UNCHECKED_CAST")
                val asked = message as KeepCommand<M>
                val (after, send) = outboxes.kept(asked.to, asked.kept.sequence, asked.kept.command)
                perform(send)
                measure()
                become(after)
            }

            is Confirmed -> when (val confirmed = outboxes.confirmed(message.to, message.sequence)) {
                null -> stay()

                else -> {
                    room.release()
                    measure()
                    perform(confirmed.second)
                    become(confirmed.first)
                }
            }

            ResendUnconfirmed -> {
                outboxes.heads().forEach {
                    meters.resent.increment()
                    perform(it)
                }
                stay()
            }

            else -> unhandled()
        }
    }.onStart { ctx -> ctx.timers.every(ResendUnconfirmed, resendAfter, ResendUnconfirmed) }
}
