package dev.surefold.actor

import dev.surefold.clock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

/** What a keyed entity answered a key (spec 0300). */
sealed interface KeyAnswer<out R> {
    /** The command was applied. */
    data object Accepted : KeyAnswer<Nothing>

    /** The command was refused for [reason], and nothing was applied. */
    data class Refused<out R>(val reason: R) : KeyAnswer<R>

    /** The entity has no record of the key. */
    data object Unknown : KeyAnswer<Nothing>
}

/** What a keyed entity is told: a command under a key, or a question about a key. */
sealed interface Keyed<out C : Any, R : Any> {
    val key: String

    /** Applies [command] once for [key]: a repeat of the key is answered as the first was, and writes nothing. */
    data class Apply<out C : Any, R : Any>(override val key: String, val command: C, val reply: Reply<KeyAnswer<R>>) :
        Keyed<C, R>

    /** Answers what [key] got, or [KeyAnswer.Unknown], from the entity's state and without deciding anything. */
    data class Applied<R : Any>(override val key: String, val reply: Reply<KeyAnswer<R>>) : Keyed<Nothing, R>

    /** Where the wire codec for keyed messages hangs, in `surefold-actor-remote`. */
    companion object
}

/** What a keyed entity decided about one command. */
class Decision<out Ev, out R : Any> internal constructor(internal val events: List<Ev>, internal val refused: R?)

/** The decisions a keyed entity's command can answer. */
class Decisions<Ev, R : Any> internal constructor() {
    /** Applies the command: [events] are written with its key, in one append. */
    fun accept(vararg events: Ev): Decision<Ev, R> = Decision(events.toList(), null)

    /** Refuses the command for [reason]: only its key and the refusal are written. */
    fun refuse(reason: R): Decision<Ev, R> = Decision(emptyList(), reason)
}

/** A key's answer, and when it was given, in epoch milliseconds. */
data class KeyRecord<out R>(val answer: KeyAnswer<R>, val at: Long)

/** A keyed entity's state: what its events built, and the answer of every key still remembered. */
data class KeyedState<S, R>(val value: S, val answers: Map<String, KeyRecord<R>> = emptyMap())

/** What a keyed entity's journal holds: the events a command decided, and the answer its key got. */
sealed interface KeyedEvent<out Ev, out R> {
    data class Decided<out Ev>(val event: Ev) : KeyedEvent<Ev, Nothing>

    /**
     * [key]'s answer, given [at] in epoch milliseconds: accepted when [refused] is null. Written last in the append
     * that holds the command's events.
     */
    data class Answered<out R>(val key: String, val refused: R?, val at: Long) : KeyedEvent<Nothing, R>
}

/** The journal codec of a keyed entity whose events are read by [codec] and its refusals by [refusals]. */
fun <Ev, R : Any> keyedEvents(codec: EventCodec<Ev>, refusals: EventCodec<R>): EventCodec<KeyedEvent<Ev, R>> =
    KeyedEvents(codec, refusals)

/**
 * A persistent behaviour whose commands each carry a key (spec 0300). [decide] answers a command once: its events and
 * its key's answer go in one append, so they can't come apart, and a repeat of the key gets the same answer and writes
 * nothing. [Keyed.Applied] answers a key from the state, for a caller that lost the answer to ask again.
 *
 * A key is remembered for [remember] after its answer: each answer written drops the keys older than that, the same way
 * on every replay, so the state does not grow forever. A repeat that arrives later is decided again, which is why
 * whatever resends a key (0299's sagas) must give up on it sooner. [snapshots] saves the state with its keys.
 */
fun <C : Any, Ev, S, R : Any> keyed(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    refusals: EventCodec<R>,
    decide: Decisions<Ev, R>.(state: S, command: C) -> Decision<Ev, R>,
    event: (state: S, event: Ev) -> S,
    remember: Duration = 30.days,
    snapshots: Snapshotting<S>? = null,
): Behaviour<Keyed<C, R>, Remembered<KeyedState<S, R>>, JournalConflict> {
    require(remember.isPositive()) { "a key remembered for $remember is never remembered" }
    val decisions = Decisions<Ev, R>()
    val window = remember.inWholeMilliseconds
    return persistent<Keyed<C, R>, KeyedEvent<Ev, R>, KeyedState<S, R>>(
        id = id,
        empty = KeyedState(empty),
        codec = keyedEvents(codec, refusals),
        snapshots = snapshots?.let { Snapshotting(it.every, KeyedStates(it.codec, refusals), it.prune) },
        command = { _, state, message ->
            val known = state.answers[message.key]?.answer
            when (message) {
                is Keyed.Applied -> none().then { message.reply(known ?: KeyAnswer.Unknown) }

                is Keyed.Apply if known != null -> none().then { message.reply(known) }

                is Keyed.Apply -> {
                    val decision = decisions.decide(state.value, message.command)
                    val written =
                        decision.events.map { KeyedEvent.Decided(it) } +
                            KeyedEvent.Answered(message.key, decision.refused, clock.get().now().toEpochMilli())
                    Effect<KeyedEvent<Ev, R>, KeyedState<S, R>>(written, null, null).then { after ->
                        message.reply(after.answers.getValue(message.key).answer)
                    }
                }
            }
        },
        event = { state, written ->
            when (written) {
                is KeyedEvent.Decided -> state.copy(value = event(state.value, written.event))

                is KeyedEvent.Answered -> {
                    val answer = written.refused?.let { KeyAnswer.Refused(it) } ?: KeyAnswer.Accepted
                    val kept = state.answers.filterValues { it.at >= written.at - window }
                    state.copy(answers = kept + (written.key to KeyRecord(answer, written.at)))
                }
            }
        },
    )
}

private class KeyedEvents<Ev, R : Any>(private val codec: EventCodec<Ev>, private val refusals: EventCodec<R>) :
    EventCodec<KeyedEvent<Ev, R>> {

    override fun encode(event: KeyedEvent<Ev, R>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            when (event) {
                is KeyedEvent.Decided -> {
                    out.writeByte(DECIDED)
                    out.write(codec.encode(event.event))
                }

                is KeyedEvent.Answered -> {
                    out.writeByte(if (event.refused == null) ACCEPTED else REFUSED)
                    out.writeUTF(event.key)
                    out.writeLong(event.at)
                    event.refused?.let { out.write(refusals.encode(it)) }
                }
            }
        }
        return bytes.toByteArray()
    }

    override fun decode(bytes: ByteArray): KeyedEvent<Ev, R> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return when (val tag = input.readByte().toInt()) {
            DECIDED -> KeyedEvent.Decided(codec.decode(input.readAllBytes()))

            ACCEPTED -> KeyedEvent.Answered(input.readUTF(), null, input.readLong())

            REFUSED -> {
                val key = input.readUTF()
                val at = input.readLong()
                KeyedEvent.Answered(key, refusals.decode(input.readAllBytes()), at)
            }

            else -> error("no keyed event has the tag $tag")
        }
    }

    private companion object {
        const val DECIDED = 1
        const val ACCEPTED = 2
        const val REFUSED = 3
    }
}

/** A keyed entity's snapshot: its state through [codec], then each remembered key with its answer and time. */
private class KeyedStates<S, R : Any>(private val codec: StateCodec<S>, private val refusals: EventCodec<R>) :
    StateCodec<KeyedState<S, R>> {

    override fun encode(state: KeyedState<S, R>): ByteArray {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).use { out ->
            out.sized(codec.encode(state.value))
            out.writeInt(state.answers.size)
            state.answers.forEach { (key, kept) ->
                out.writeUTF(key)
                out.writeLong(kept.at)
                when (val answer = kept.answer) {
                    is KeyAnswer.Refused -> out.sized(refusals.encode(answer.reason))
                    KeyAnswer.Accepted,
                    KeyAnswer.Unknown -> out.writeInt(-1)
                }
            }
        }
        return bytes.toByteArray()
    }

    override fun decode(bytes: ByteArray): KeyedState<S, R> {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        val value = codec.decode(input.sized())
        val answers =
            List(input.readInt()) {
                val key = input.readUTF()
                val at = input.readLong()
                val size = input.readInt()
                val answer =
                    if (size < 0) KeyAnswer.Accepted else KeyAnswer.Refused(refusals.decode(input.readNBytes(size)))
                key to KeyRecord(answer, at)
            }
        return KeyedState(value, answers.toMap())
    }

    private fun DataOutputStream.sized(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.sized(): ByteArray = readNBytes(readInt())
}
