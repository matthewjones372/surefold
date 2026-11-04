package dev.surefold.actor.remote

import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed

/**
 * How keyed messages (spec 0300) cross nodes: each command through [commands], with its key and the reply its answer
 * goes back to, and each refusal through [refusals].
 */
fun <C : Any, R : Any> Keyed.Companion.codec(
    commands: MessageCodec<C>,
    refusals: MessageCodec<R>,
): MessageCodec<Keyed<C, R>> {
    val answers = KeyAnswers(refusals)
    return object : MessageCodec<Keyed<C, R>> {
        override fun write(message: Keyed<C, R>, out: WireOut) = when (message) {
            is Keyed.Apply -> {
                out.int(APPLY)
                out.string(message.key)
                commands.write(message.command, out)
                out.reply(message.reply, answers)
            }

            is Keyed.Applied -> {
                out.int(APPLIED)
                out.string(message.key)
                out.reply(message.reply, answers)
            }
        }

        override fun read(input: WireIn): Keyed<C, R> = when (val tag = input.int()) {
            APPLY -> Keyed.Apply(input.string(), commands.read(input), input.reply(answers))
            APPLIED -> Keyed.Applied(input.string(), input.reply(answers))
            else -> throw WireException("no keyed message has the tag $tag")
        }
    }
}

private class KeyAnswers<R : Any>(private val refusals: MessageCodec<R>) : MessageCodec<KeyAnswer<R>> {
    override fun write(message: KeyAnswer<R>, out: WireOut) = when (message) {
        KeyAnswer.Accepted -> out.int(ACCEPTED)

        KeyAnswer.Unknown -> out.int(UNKNOWN)

        is KeyAnswer.Refused -> {
            out.int(REFUSED)
            refusals.write(message.reason, out)
        }
    }

    override fun read(input: WireIn): KeyAnswer<R> = when (val tag = input.int()) {
        ACCEPTED -> KeyAnswer.Accepted
        UNKNOWN -> KeyAnswer.Unknown
        REFUSED -> KeyAnswer.Refused(refusals.read(input))
        else -> throw WireException("no key answer has the tag $tag")
    }
}

private const val APPLY = 1
private const val APPLIED = 2
private const val ACCEPTED = 1
private const val REFUSED = 2
private const val UNKNOWN = 3
