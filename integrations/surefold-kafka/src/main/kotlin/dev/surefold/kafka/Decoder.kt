package dev.surefold.kafka

import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.bind
import dev.surefold.fail.mapLeft
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.header.Header
import org.apache.kafka.common.header.Headers
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer

/**
 * A key or value read from bytes in the stream rather than in the connector's poll, where a throw would end the
 * consumer: a throw [transient] accepts stays a defect for `restartOnDefect`, and any other is a [DecodeError].
 */
class Decoder<out A>(deserializer: Deserializer<out A>, private val transient: (Throwable) -> Boolean) {

    private val read: (String, Headers, ByteArray?) -> A = deserializer::deserialize

    internal fun decode(
        record: ConsumerRecord<ByteArray?, ByteArray?>,
        part: DecodeError.Part,
    ): Either<DecodeError, A> {
        val bytes = if (part == DecodeError.Part.Key) record.key() else record.value()
        return Either.catch { read(record.topic(), record.headers(), bytes) }
            .mapLeft { thrown -> if (transient(thrown)) throw thrown else DecodeError(record, part, thrown) }
    }

    companion object {
        /** UTF-8, which nothing transient stands behind. */
        fun string(): Decoder<String> = Decoder(StringDeserializer()) { false }
    }
}

/** A record that could not be read: where it was, what was in it, and why, which is what a dead letter needs. */
class DecodeError internal constructor(
    record: ConsumerRecord<ByteArray?, ByteArray?>,
    val part: Part,
    val cause: Throwable,
) {

    enum class Part { Key, Value }

    val topic: String = record.topic()
    val partition: Int = record.partition()
    val offset: Long = record.offset()
    val key: ByteArray? = record.key()
    val value: ByteArray? = record.value()
    val headers: List<Header> = record.headers().toList()

    override fun toString(): String = "DecodeError($part at $topic-$partition@$offset: $cause)"
}

internal fun <K, V> ConsumerRecord<ByteArray?, ByteArray?>.decoded(
    key: Decoder<K>,
    value: Decoder<V>,
): Either<DecodeError, ConsumerRecord<K, V>> = attempt {
    ConsumerRecord(
        topic(),
        partition(),
        offset(),
        timestamp(),
        timestampType(),
        serializedKeySize(),
        serializedValueSize(),
        key.decode(this@decoded, DecodeError.Part.Key).bind(),
        value.decode(this@decoded, DecodeError.Part.Value).bind(),
        headers(),
        leaderEpoch(),
    )
}
