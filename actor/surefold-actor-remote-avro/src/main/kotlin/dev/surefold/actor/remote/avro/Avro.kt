package dev.surefold.actor.remote.avro

import dev.surefold.actor.Reply
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import org.apache.avro.AvroRuntimeException
import org.apache.avro.Schema
import org.apache.avro.message.BinaryMessageDecoder
import org.apache.avro.message.BinaryMessageEncoder
import org.apache.avro.message.SchemaStore
import org.apache.avro.specific.SpecificData
import org.apache.avro.specific.SpecificRecord
import java.io.IOException

/**
 * Codecs for records Avro generated. Each is written in Avro's single-object encoding, its writer schema's
 * fingerprint and then the record, and read against the reader's own schema: a record another node wrote with an
 * older or a newer schema is resolved through the [SchemaStore] the service gives, so two nodes on different versions
 * of a record still understand each other.
 */
object Avro {

    /** Records of [type], read against [type]'s own schema; [schemas] finds the schemas other nodes wrote with. */
    fun <R : SpecificRecord> codec(type: Class<R>, schemas: SchemaStore): MessageCodec<R> {
        val records = Records(type, schemas)
        return object : MessageCodec<R> {
            override fun write(message: R, out: WireOut) = out.bytes(records.encode(message))

            override fun read(input: WireIn): R = records.decode(input.bytes())
        }
    }

    /**
     * An ask whose request is a record of [type]: [make] a message of [Q] from the request and the reply, which
     * crosses as a surefold reply answered in [answers], beside the record.
     */
    fun <R : SpecificRecord, A : Any, Q : Any> asked(
        type: Class<R>,
        schemas: SchemaStore,
        answers: MessageCodec<A>,
        make: (R, Reply<A>) -> Q,
        requestOf: (Q) -> R,
        replyOf: (Q) -> Reply<A>,
    ): MessageCodec<Q> {
        val records = Records(type, schemas)
        return object : MessageCodec<Q> {
            override fun write(message: Q, out: WireOut) {
                out.reply(replyOf(message), answers)
                out.bytes(records.encode(requestOf(message)))
            }

            override fun read(input: WireIn): Q {
                val reply = input.reply(answers)
                return make(records.decode(input.bytes()), reply)
            }
        }
    }
}

/**
 * Avro's own encoder and decoder for one record type; both are safe to share between threads. Avro 1.12 makes a
 * record's class only if it is trusted, by default by the `org.apache.avro.SERIALIZABLE_PACKAGES` system property;
 * the class a service hands to [Avro.codec] is trusted without it, and records nested in it still need the property.
 */
private class Records<R : SpecificRecord>(type: Class<R>, schemas: SchemaStore) {
    private val model = Trusting(type)
    private val schema = model.getSchema(type)
    private val encoder = BinaryMessageEncoder<R>(model, schema)
    private val decoder = BinaryMessageDecoder<R>(model, schema, schemas)

    fun encode(record: R): ByteArray = encoder.encode(record).let { buffer ->
        ByteArray(buffer.remaining()).also(buffer::get)
    }

    fun decode(bytes: ByteArray): R = try {
        decoder.decode(bytes)
    } catch (unread: AvroRuntimeException) {
        throw WireException("not a record Avro can read: ${unread.message}").apply { initCause(unread) }
    } catch (unread: IOException) {
        throw WireException("not a record Avro can read: ${unread.message}").apply { initCause(unread) }
    } catch (untrusted: SecurityException) {
        throw WireException("a record of a class Avro does not trust: ${untrusted.message}").apply {
            initCause(untrusted)
        }
    }
}

/** The model [type] was generated with, which makes [type] itself without asking whether its package is trusted. */
private class Trusting(private val type: Class<*>) : SpecificData(type.classLoader) {
    init {
        getForClass(type).conversions.forEach(::addLogicalTypeConversion)
    }

    override fun getClass(schema: Schema): Class<*>? = if (schema.fullName ==
        type.name
    ) type else super.getClass(schema)
}
