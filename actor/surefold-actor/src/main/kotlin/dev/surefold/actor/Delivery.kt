package dev.surefold.actor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Where a command sent reliably came from (spec 0079): its [producer], the entity it is [to], its [sequence] among that
 * producer's commands to that entity, counting from 1, and where to confirm it.
 */
data class Delivery(val producer: String, val to: String, val sequence: Long, val confirmTo: ActorRef<Confirmed>) {
    /** Tells the producer this command was handled, so it stops sending it again. */
    fun confirm() = confirmTo.tell(Confirmed(to, sequence))

    /** This delivery with nowhere to confirm to: how a durable producer keeps a command (spec 0085). */
    fun blank(): Delivery = copy(confirmTo = NoOne)

    /** Where a blank delivery confirms: nobody, since the producer that kept it may be gone. */
    object NoOne : ActorRef<Confirmed> {
        override val address = Address("", "/nobody", 0)

        override fun tell(message: Confirmed) = Unit
    }
}

/**
 * A command a producer may send more than once. The command type carries its [delivery], so an entity's protocol stays
 * its own: a command sent plainly is one that does not implement this.
 */
interface Delivered {
    val delivery: Delivery

    /**
     * This command with [delivery] in place of its own: how a durable producer sends a kept command again, naming
     * itself as it runs now (spec 0085). A data class implements it as `copy(delivery = delivery)`; a command only a
     * producer in memory sends need not.
     */
    fun redeliver(delivery: Delivery): Delivered =
        throw UnsupportedOperationException("${this::class.simpleName} is sent durably, so it must implement redeliver")
}

/** The command numbered [sequence] to entity [to] was handled; its producer need not send it again. */
data class Confirmed(val to: String, val sequence: Long)

/**
 * [behaviour], confirming each [Delivered] command once its turn has returned, whatever the turn answered. A turn that
 * fails confirms nothing, so the producer sends the command again, and one that stashes the command confirms it when it
 * is handled once put back. Duplicates reach the turn: a persistent behaviour drops those it remembers, and any other
 * must handle a command twice as once.
 */
fun <M : Any, S, E> delivered(behaviour: Behaviour<M, S, E>): Behaviour<M, S, E> =
    Behaviour(
        initial = behaviour.initial,
        turn = { ctx, state, message ->
            if (message is Delivered) {
                val stashing = Stashing(ctx)
                behaviour.turn(this, stashing, state, message).also {
                    if (!stashing.kept(message)) message.delivery.confirm()
                }
            } else {
                behaviour.turn(this, ctx, state, message)
            }
        },
        signal = behaviour.signal,
        start = behaviour.start,
        // A run confirms what it ran, once it returns: never what it left unrun by stopping first.
        turns =
            behaviour.turns?.let { turns ->
                { ctx, state, messages ->
                    val stashing = Stashing(ctx)
                    turns(this, stashing, state, messages).also { batched ->
                        messages.forEach { message ->
                            val handled = batched.unrun.none { it === message } && !stashing.kept(message)
                            if (message is Delivered && handled) message.delivery.confirm()
                        }
                    }
                }
            },
        batch = behaviour.batch,
    )

/** [ctx], keeping note of what a turn stashes: a command stashed is not handled yet, so nothing may say it was. */
internal class Stashing<M : Any>(private val ctx: Ctx<M>) : Ctx<M> by ctx {
    private val stashed = ArrayList<Any>(1)

    override fun stash(message: M) {
        ctx.stash(message)
        stashed += message
    }

    /** Whether [message] itself, not an equal one, was stashed through this. */
    fun kept(message: Any): Boolean = stashed.any { it === message }
}

private val MARK = "\u0000surefold:delivered\u0000".toByteArray()

/**
 * Whether [bytes] in a journal are the mark a persistent entity writes of a delivery it handled (spec 0079), not one of
 * its events. A follower of the feed skips them.
 */
fun isDeliveryMark(bytes: ByteArray): Boolean = bytes.size >= MARK.size && bytes.copyOf(MARK.size).contentEquals(MARK)

/** The mark of [delivery], written with the events its command persisted. */
internal fun mark(delivery: Delivery): ByteArray = MARK + "${delivery.sequence}:${delivery.producer}".toByteArray()

/** The producer and sequence number a mark records. */
internal fun unmark(bytes: ByteArray): Pair<String, Long> {
    val (sequence, producer) = String(bytes, MARK.size, bytes.size - MARK.size).split(":", limit = 2)
    return producer to sequence.toLong()
}

/**
 * [remembered] as a snapshot's bytes: [codec]'s alone when no delivery is remembered, so snapshots of entities that are
 * never sent to reliably are the service's own bytes; otherwise a mark, the last sequence number per producer, and then
 * [codec]'s.
 */
internal fun <S> encode(codec: StateCodec<S>, remembered: Remembered<S>): ByteArray {
    val state = codec.encode(remembered.value)
    if (remembered.delivered.isEmpty()) return state
    val out = ByteArrayOutputStream()
    DataOutputStream(out).use { data ->
        data.write(MARK)
        data.writeInt(remembered.delivered.size)
        remembered.delivered.forEach { (producer, sequence) ->
            data.writeUTF(producer)
            data.writeLong(sequence)
        }
        data.write(state)
    }
    return out.toByteArray()
}

/** The state and the deliveries a snapshot's [bytes] hold, at [sequence]. */
internal fun <S> decode(codec: StateCodec<S>, bytes: ByteArray, sequence: Long): Remembered<S> {
    if (!isDeliveryMark(bytes)) return Remembered(codec.decode(bytes), sequence)
    val data = DataInputStream(ByteArrayInputStream(bytes, MARK.size, bytes.size - MARK.size))
    val delivered = buildMap { repeat(data.readInt()) { put(data.readUTF(), data.readLong()) } }
    return Remembered(codec.decode(data.readAllBytes()), sequence, delivered)
}
