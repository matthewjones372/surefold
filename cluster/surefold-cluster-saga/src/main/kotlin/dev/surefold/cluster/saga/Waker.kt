package dev.surefold.cluster.saga

import dev.surefold.Schedule
import dev.surefold.Shared
import dev.surefold.actor.AskFailure
import dev.surefold.actor.EventCodec
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.onStart
import dev.surefold.actor.persistent
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.singleton
import dev.surefold.fail.Either
import dev.surefold.fail.bind
import dev.surefold.retrying
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.WeakHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

private const val SAGAS = "surefold-sagas"

/** How long a runner waits for the waker to list a saga, and then how long before it asks again. */
private val LIST_WITHIN = 5.seconds
private val ASK_AGAIN_AFTER = 100.milliseconds

/** One saga, by its kind and id. */
private data class Saga(val kind: String, val id: String)

/** What the `surefold-sagas` singleton handles. */
private sealed interface Wakes {
    /** The saga is about to be started: list it, then say so. */
    data class Began(val saga: Saga, val reply: Reply<Unit>) : Wakes

    data class Ended(val saga: Saga) : Wakes

    /** A node can now wake [kind]'s sagas: the one the waker runs on wakes them, if it is that node. */
    data class Known(val kind: String) : Wakes

    /** The waker's own word, on its start and when a member is removed. */
    data object WakeAll : Wakes
}

/** What the waker writes: a saga listed as unfinished, or one taken off. */
private sealed interface Listing {
    val saga: Saga

    data class Listed(override val saga: Saga) : Listing

    data class Unlisted(override val saga: Saga) : Listing
}

// Locked rather than a `Shared`: making a waker spawns its singleton, and a step may run more than once.
private val wakers = WeakHashMap<Cluster, Waker>()

/** This node's part in the cluster's `surefold-sagas` singleton: one per cluster, however many kinds run on it. */
internal fun Cluster.waker(): Waker = synchronized(wakers) { wakers.getOrPut(this) { Waker(this) } }

/**
 * The `surefold-sagas` singleton, which lists every saga not yet ended and tells each one `Wake` when it starts and
 * when a member is removed, and this node's way to reach it. Only the kinds run on the node it runs on are woken.
 */
internal class Waker(private val cluster: Cluster) {
    private val kinds = Shared(emptyMap<String, (String) -> Unit>(), "surefold.sagas.kinds")

    private val singleton = cluster.singleton(SAGAS, WakesCodec) { waking() }

    /** Lets the waker on this node wake [kind]'s sagas through [wake]. */
    fun kind(kind: String, wake: (id: String) -> Unit) {
        val added = kinds.modify { known -> if (kind in known) known to false else (known + (kind to wake)) to true }
        require(added) { "the saga kind $kind already runs on this cluster" }
        singleton.tell(Wakes.Known(kind))
    }

    /**
     * Lists the saga before it is started, asking as long as it takes, so this never answers on the left: unlisted, a
     * crash would leave it asleep.
     */
    fun began(kind: String, id: String): Either<AskFailure, Unit> {
        val saga = Saga(kind, id)
        return Schedule.spaced<AskFailure>(ASK_AGAIN_AFTER).retrying {
            singleton.ask<Wakes, Unit>(LIST_WITHIN) { reply -> Wakes.Began(saga, reply) }.bind()
        }
    }

    /** Takes an ended saga off the list; one whose word is lost is taken off when it is next woken. */
    fun ended(kind: String, id: String) = singleton.tell(Wakes.Ended(Saga(kind, id)))

    private fun waking() =
        persistent<Wakes, Listing, Set<Saga>>(
                id = PersistenceId(SAGAS, SAGAS),
                empty = emptySet(),
                codec = ListingCodec,
                command = { _, unfinished, message ->
                    when (message) {
                        is Wakes.Began if message.saga in unfinished -> none().then { _ -> message.reply(Unit) }
                        is Wakes.Began -> persist(Listing.Listed(message.saga)).then { _ -> message.reply(Unit) }
                        is Wakes.Ended if message.saga in unfinished -> persist(Listing.Unlisted(message.saga))
                        is Wakes.Ended -> none()
                        is Wakes.Known -> none().then { _ -> wake(unfinished.filter { it.kind == message.kind }) }
                        Wakes.WakeAll -> none().then { wake(unfinished) }
                    }
                },
                event = { unfinished, listing ->
                    when (listing) {
                        is Listing.Listed -> unfinished + listing.saga
                        is Listing.Unlisted -> unfinished - listing.saga
                    }
                },
            )
            .onStart { ctx ->
                val self = ctx.self
                val removals =
                    behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
                        if (event is MemberEvent.Removed) self.tell(Wakes.WakeAll)
                        stay()
                    }
                cluster.subscribe(ctx.spawn("members", removals))
                ctx.timers.after(Wakes.WakeAll, Duration.ZERO, Wakes.WakeAll)
            }

    private fun wake(sagas: Iterable<Saga>) = sagas.forEach { saga -> kinds.peek()[saga.kind]?.invoke(saga.id) }
}

private object WakesCodec : MessageCodec<Wakes> {
    override fun write(message: Wakes, out: WireOut) =
        when (message) {
            is Wakes.Began -> {
                out.int(BEGAN)
                out.saga(message.saga)
                out.reply(message.reply, Codecs.unit)
            }

            is Wakes.Ended -> {
                out.int(ENDED)
                out.saga(message.saga)
            }

            is Wakes.Known -> {
                out.int(KNOWN)
                out.string(message.kind)
            }

            Wakes.WakeAll -> error("$message never leaves its node")
        }

    override fun read(input: WireIn): Wakes =
        when (val tag = input.int()) {
            BEGAN -> Wakes.Began(input.saga(), input.reply(Codecs.unit))
            ENDED -> Wakes.Ended(input.saga())
            KNOWN -> Wakes.Known(input.string())
            else -> throw WireException("no waker message has the tag $tag")
        }

    private fun WireOut.saga(saga: Saga) {
        string(saga.kind)
        string(saga.id)
    }

    private fun WireIn.saga() = Saga(string(), string())
}

private object ListingCodec : EventCodec<Listing> {
    override fun encode(event: Listing): ByteArray =
        ByteArrayOutputStream()
            .also { bytes ->
                DataOutputStream(bytes).use { data ->
                    data.writeByte(if (event is Listing.Listed) LISTED else UNLISTED)
                    data.writeUTF(event.saga.kind)
                    data.writeUTF(event.saga.id)
                }
            }
            .toByteArray()

    override fun decode(bytes: ByteArray): Listing =
        DataInputStream(ByteArrayInputStream(bytes)).use { data ->
            val tag = data.readByte().toInt()
            val saga = Saga(data.readUTF(), data.readUTF())
            when (tag) {
                LISTED -> Listing.Listed(saga)
                UNLISTED -> Listing.Unlisted(saga)
                else -> error("no waker event has the tag $tag")
            }
        }
}

private const val BEGAN = 1
private const val ENDED = 2
private const val KNOWN = 3
private const val LISTED = 1
private const val UNLISTED = 2
