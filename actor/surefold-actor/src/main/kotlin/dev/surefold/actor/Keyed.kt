package dev.surefold.actor

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

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

/** A keyed entity's state: what its events built, and every key's answer. */
data class KeyedState<S, R>(val value: S, val answers: Map<String, KeyAnswer<R>> = emptyMap())

/** What a keyed entity's journal holds: the events a command decided, and the answer its key got. */
sealed interface KeyedEvent<out Ev, out R> {
    data class Decided<out Ev>(val event: Ev) : KeyedEvent<Ev, Nothing>

    /** [key]'s answer: accepted when [refused] is null. Written last in the append that holds the command's events. */
    data class Answered<out R>(val key: String, val refused: R?) : KeyedEvent<Nothing, R>
}

/** The journal codec of a keyed entity whose events are read by [codec] and its refusals by [refusals]. */
fun <Ev, R : Any> keyedEvents(codec: EventCodec<Ev>, refusals: EventCodec<R>): EventCodec<KeyedEvent<Ev, R>> =
    KeyedEvents(codec, refusals)

/**
 * A persistent behaviour whose commands each carry a key (spec 0300). [decide] answers a command once: its events and
 * its key's answer go in one append, so they can't come apart, and a repeat of the key gets the same answer and
 * writes nothing. [Keyed.Applied] answers a key from the state, for a caller that lost the answer to ask again.
 */
fun <C : Any, Ev, S, R : Any> keyed(
    id: PersistenceId,
    empty: S,
    codec: EventCodec<Ev>,
    refusals: EventCodec<R>,
    decide: Decisions<Ev, R>.(state: S, command: C) -> Decision<Ev, R>,
    event: (state: S, event: Ev) -> S,
): Behaviour<Keyed<C, R>, Remembered<KeyedState<S, R>>, JournalConflict> {
    val decisions = Decisions<Ev, R>()
    return persistent<Keyed<C, R>, KeyedEvent<Ev, R>, KeyedState<S, R>>(
        id = id,
        empty = KeyedState(empty),
        codec = keyedEvents(codec, refusals),
        command = { _, state, message ->
            val known = state.answers[message.key]
            when (message) {
                is Keyed.Applied -> none().then { message.reply(known ?: KeyAnswer.Unknown) }

                is Keyed.Apply if known != null -> none().then { message.reply(known) }

                is Keyed.Apply -> {
                    val decision = decisions.decide(state.value, message.command)
                    val written = decision.events.map { KeyedEvent.Decided(it) } +
                        KeyedEvent.Answered(message.key, decision.refused)
                    Effect<KeyedEvent<Ev, R>, KeyedState<S, R>>(written, null, null).then { after ->
                        message.reply(after.answers.getValue(message.key))
                    }
                }
            }
        },
        event = { state, written ->
            when (written) {
                is KeyedEvent.Decided -> state.copy(value = event(state.value, written.event))

                is KeyedEvent.Answered -> {
                    val answer = written.refused?.let { KeyAnswer.Refused(it) } ?: KeyAnswer.Accepted
                    state.copy(answers = state.answers + (written.key to answer))
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
            ACCEPTED -> KeyedEvent.Answered(input.readUTF(), null)
            REFUSED -> KeyedEvent.Answered(input.readUTF(), refusals.decode(input.readAllBytes()))
            else -> error("no keyed event has the tag $tag")
        }
    }

    private companion object {
        const val DECIDED = 1
        const val ACCEPTED = 2
        const val REFUSED = 3
    }
}
