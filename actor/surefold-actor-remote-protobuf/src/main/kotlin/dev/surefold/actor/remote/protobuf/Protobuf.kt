package dev.surefold.actor.remote.protobuf

import com.google.protobuf.InvalidProtocolBufferException
import com.google.protobuf.Message
import com.google.protobuf.Parser
import dev.surefold.actor.Reply
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut

/** Codecs for messages Protobuf generated, so that a service already describing them in `.proto` writes none. */
object Protobuf {

    /** A generated message as its own Protobuf bytes. */
    fun <M : Message> codec(parser: Parser<M>): MessageCodec<M> = object : MessageCodec<M> {
        override fun write(message: M, out: WireOut) = out.bytes(message.toByteArray())

        override fun read(input: WireIn): M = parse(parser, input.bytes())
    }

    /**
     * A protocol of several generated messages, each written after the tag it is given here. A tag is part of the
     * wire form, so it is never reused for another message; building one with a tag or a type twice fails at once.
     */
    fun oneOf(build: OneOf.() -> Unit): MessageCodec<Message> = OneOf().apply(build).codec()

    /**
     * An ask whose request is a generated message: [make] a message of [Q] from the request and the reply, which
     * crosses as a surefold reply answered in [answers], beside the request's bytes.
     */
    fun <R : Message, A : Any, Q : Any> asked(
        request: Parser<R>,
        answers: MessageCodec<A>,
        make: (R, Reply<A>) -> Q,
        requestOf: (Q) -> R,
        replyOf: (Q) -> Reply<A>,
    ): MessageCodec<Q> = object : MessageCodec<Q> {
        override fun write(message: Q, out: WireOut) {
            out.reply(replyOf(message), answers)
            out.bytes(requestOf(message).toByteArray())
        }

        override fun read(input: WireIn): Q {
            val reply = input.reply(answers)
            return make(parse(request, input.bytes()), reply)
        }
    }

    /** The messages of a [oneOf] protocol, each under its tag. */
    class OneOf internal constructor() {
        private val parsers = HashMap<Int, Parser<out Message>>()
        private val tags = HashMap<Class<*>, Int>()

        /** [M] under [tag], read with [parser]. */
        inline fun <reified M : Message> message(tag: Int, parser: Parser<M>) = message(tag, M::class.java, parser)

        fun <M : Message> message(tag: Int, type: Class<M>, parser: Parser<M>) {
            require(tag !in parsers) { "the tag $tag is given twice" }
            require(type !in tags) { "${type.simpleName} is given twice, under ${tags[type]} and $tag" }
            parsers[tag] = parser
            tags[type] = tag
        }

        internal fun codec(): MessageCodec<Message> {
            val parsers = parsers.toMap()
            val tags = tags.toMap()
            return object : MessageCodec<Message> {
                override fun write(message: Message, out: WireOut) {
                    val tag = requireNotNull(tags[message.javaClass]) {
                        "${message.javaClass.simpleName} has no tag in this protocol"
                    }
                    out.int(tag)
                    out.bytes(message.toByteArray())
                }

                override fun read(input: WireIn): Message {
                    val tag = input.int()
                    val parser = parsers[tag] ?: throw WireException("no message of this protocol has the tag $tag")
                    return parse(parser, input.bytes())
                }
            }
        }
    }
}

private fun <M : Message> parse(parser: Parser<M>, bytes: ByteArray): M = try {
    parser.parseFrom(bytes)
} catch (broken: InvalidProtocolBufferException) {
    throw WireException("not a message Protobuf can read: ${broken.message}").apply { initCause(broken) }
}
