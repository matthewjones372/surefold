@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.surefold.actor.remote.kotlinx

import dev.surefold.actor.EventCodec
import dev.surefold.actor.Reply
import dev.surefold.actor.StateCodec
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import kotlinx.serialization.BinaryFormat
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.protobuf.ProtoBuf
import kotlinx.serialization.protobuf.schema.ProtoBufSchemaGenerator
import kotlinx.serialization.serializer
import java.io.ByteArrayOutputStream
import kotlin.reflect.KClass

/**
 * Codecs for a service's own `@Serializable` data classes (spec 0093), so the classes are the only definition of
 * what crosses a node or lands in the journal. ProtoBuf by default; any kotlinx [BinaryFormat] otherwise.
 */
object Kotlinx {

    /** One class as its own bytes, between nodes. */
    fun <M : Any> codec(serializer: KSerializer<M>, format: BinaryFormat = ProtoBuf): MessageCodec<M> =
        object : MessageCodec<M> {
            override fun write(message: M, out: WireOut) = out.bytes(format.encodeToByteArray(serializer, message))

            override fun read(input: WireIn): M = onTheWire { format.decodeFromByteArray(serializer, input.bytes()) }
        }

    /** One class as its own bytes, in the journal. */
    fun <E> events(serializer: KSerializer<E>, format: BinaryFormat = ProtoBuf): EventCodec<E> =
        object : EventCodec<E> {
            override fun encode(event: E): ByteArray = format.encodeToByteArray(serializer, event)

            override fun decode(bytes: ByteArray): E = format.decodeFromByteArray(serializer, bytes)
        }

    /** One class as its own bytes, as a snapshot. */
    fun <S> state(serializer: KSerializer<S>, format: BinaryFormat = ProtoBuf): StateCodec<S> = object : StateCodec<S> {
        override fun encode(state: S): ByteArray = format.encodeToByteArray(serializer, state)

        override fun decode(bytes: ByteArray): S = format.decodeFromByteArray(serializer, bytes)
    }

    /**
     * Several classes under [T], each under a tag given here. A tag is part of the stored and sent form, so it is never
     * reused for another class; building a table with a tag or a class twice fails at once.
     */
    fun <T : Any> oneOf(format: BinaryFormat = ProtoBuf, build: OneOf<T>.() -> Unit): OneOf<T> =
        OneOf<T>(format).apply(build).also(OneOf<T>::built)

    /**
     * An ask whose request is a `@Serializable` class: [make] a message of [Q] from the request and the reply, which
     * crosses as a surefold reply answered in [answers], beside the request's bytes.
     */
    fun <R : Any, A : Any, Q : Any> asked(
        request: KSerializer<R>,
        answers: MessageCodec<A>,
        make: (R, Reply<A>) -> Q,
        requestOf: (Q) -> R,
        replyOf: (Q) -> Reply<A>,
        format: BinaryFormat = ProtoBuf,
    ): MessageCodec<Q> = object : MessageCodec<Q> {
        override fun write(message: Q, out: WireOut) {
            out.reply(replyOf(message), answers)
            out.bytes(format.encodeToByteArray(request, requestOf(message)))
        }

        override fun read(input: WireIn): Q {
            val reply = input.reply(answers)
            return make(onTheWire { format.decodeFromByteArray(request, input.bytes()) }, reply)
        }
    }

    /**
     * The `.proto` for [tables] in [packageName]: each table as a message named by its [OneOf.name] with a `oneof`
     * whose field numbers are its tags, which is exactly how a table writes, and every class it holds.
     */
    fun proto(packageName: String, vararg tables: OneOf<*>): String {
        val classes = tables.flatMap { table -> table.entries.map { it.serializer.descriptor } }.distinct()
        val generated = ProtoBufSchemaGenerator.generateSchemaText(classes, packageName)
        val envelopes = tables.joinToString("\n") { it.envelope() }
        return generated.trimEnd() + "\n\n" + envelopes
    }

    /** The classes of a [oneOf] table, each under its tag. */
    class OneOf<T : Any> internal constructor(private val format: BinaryFormat) {
        internal class Entry<M : Any>(val tag: Int, val type: KClass<M>, val serializer: KSerializer<M>)

        private val byTag = LinkedHashMap<Int, Entry<out T>>()
        private val byType = HashMap<KClass<*>, Entry<out T>>()

        internal val entries: Collection<Entry<out T>> get() = byTag.values

        /** What [Kotlinx.proto] names the envelope message; the root type's simple name unless given. */
        var name: String? = null

        /** [M] under [tag], written by its generated serializer. */
        inline fun <reified M : T> message(tag: Int) = message(tag, M::class, serializer<M>())

        fun <M : T> message(tag: Int, type: KClass<M>, serializer: KSerializer<M>) {
            require(tag > 0) { "a tag is a protobuf field number, so at least 1; $tag is not" }
            require(tag !in byTag) { "the tag $tag is given twice" }
            require(type !in byType) { "${type.simpleName} is given twice, under ${byType[type]?.tag} and $tag" }
            val entry = Entry(tag, type, serializer)
            byTag[tag] = entry
            byType[type] = entry
        }

        internal fun built() = require(byTag.isNotEmpty()) { "a table of no classes reads nothing" }

        /** The table between nodes. */
        fun messages(): MessageCodec<T> = object : MessageCodec<T> {
            override fun write(message: T, out: WireOut) = out.bytes(write(message))

            override fun read(input: WireIn): T = onTheWire { read(input.bytes()) }
        }

        /** The table in the journal. */
        fun events(): EventCodec<T> = object : EventCodec<T> {
            override fun encode(event: T): ByteArray = write(event)

            override fun decode(bytes: ByteArray): T = read(bytes)
        }

        /** The table as a snapshot. */
        fun state(): StateCodec<T> = object : StateCodec<T> {
            override fun encode(state: T): ByteArray = write(state)

            override fun decode(bytes: ByteArray): T = read(bytes)
        }

        /** The class's bytes as field `tag` of a message: a protobuf `oneof`, whatever the format inside. */
        fun write(value: T): ByteArray {
            val entry = requireNotNull(byType[value::class]) { "${value::class.simpleName} has no tag in this table" }
            val body = encoded(entry, value)
            return ByteArrayOutputStream(body.size + 2 * MAX_VARINT).apply {
                varint(entry.tag.toLong() shl TAG_SHIFT or LENGTH_DELIMITED)
                varint(body.size.toLong())
                write(body)
            }.toByteArray()
        }

        /** The value [write] wrote; a tag this table does not hold is a [SerializationException]. */
        fun read(bytes: ByteArray): T {
            val reading = Reading(bytes)
            val key = reading.varint()
            if (key and WIRE_TYPE != LENGTH_DELIMITED) {
                throw SerializationException("not a table's value: wire type ${key and WIRE_TYPE}")
            }
            val tag = (key ushr TAG_SHIFT).toInt()
            val entry = byTag[tag] ?: throw SerializationException("no class of this table has the tag $tag")
            val size = reading.varint().toInt()
            if (size != bytes.size - reading.at) {
                throw SerializationException("a value of ${bytes.size} bytes says it holds $size")
            }
            return format.decodeFromByteArray(entry.serializer, bytes.copyOfRange(reading.at, bytes.size))
        }

        @Suppress("UNCHECKED_CAST")
        private fun <M : Any> encoded(entry: Entry<M>, value: Any): ByteArray =
            format.encodeToByteArray(entry.serializer, value as M)

        internal fun envelope(): String {
            val root = entries.first().serializer.descriptor.serialName.substringBeforeLast('.')
            val envelope = name ?: root.substringAfterLast('.')
            val fields = entries.joinToString("\n") { entry ->
                val type = entry.serializer.descriptor.serialName.substringAfterLast('.')
                "    $type ${snake(type)} = ${entry.tag};"
            }
            return "// How surefold writes this table (spec 0093): one of these, its field number the class's tag.\n" +
                "message $envelope {\n  oneof value {\n$fields\n  }\n}\n"
        }
    }
}

private const val LENGTH_DELIMITED = 2L

/** A protobuf key is the field number shifted past the three bits of its wire type. */
private const val TAG_SHIFT = 3
private const val WIRE_TYPE = 7L
private const val MAX_VARINT = 10

@Suppress("MagicNumber")
private fun ByteArrayOutputStream.varint(value: Long) {
    var rest = value
    while (rest and 0x7fL.inv() != 0L) {
        write(((rest and 0x7f) or 0x80).toInt())
        rest = rest ushr 7
    }
    write(rest.toInt())
}

private class Reading(private val bytes: ByteArray) {
    var at = 0

    @Suppress("MagicNumber")
    fun varint(): Long {
        var value = 0L
        var shift = 0
        while (true) {
            if (at >= bytes.size || shift >= 64) throw SerializationException("a varint runs past the end")
            val byte = bytes[at++].toLong()
            value = value or ((byte and 0x7f) shl shift)
            if (byte and 0x80 == 0L) return value
            shift += 7
        }
    }
}

private fun snake(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase()

/** kotlinx's own failure to read, as the wire's: a node that sent bytes it cannot read is a wire fault. */
private inline fun <A> onTheWire(read: () -> A): A = try {
    read()
} catch (broken: SerializationException) {
    throw WireException("not a message kotlinx can read: ${broken.message}").apply { initCause(broken) }
} catch (broken: IllegalArgumentException) {
    throw WireException("not a message kotlinx can read: ${broken.message}").apply { initCause(broken) }
}
