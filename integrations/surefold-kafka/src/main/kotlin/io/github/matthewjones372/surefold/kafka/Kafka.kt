@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Either
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.runFold
import org.apache.kafka.clients.consumer.ConsumerRecord

/**
 * A topic by name, so a subscription's arguments cannot be mistaken for a group id or a server.
 *
 * A data class and not a value class: Kotlin refuses a value class as a vararg.
 */
data class Topic(val name: String)

/**
 * Every topic whose name [pattern] matches, for [Kafka.consume] to subscribe to (spec 0159): those that exist now, and
 * those created while it runs, which it finds as it refreshes its metadata, every `metadata.max.age.ms`.
 */
class Topics private constructor(internal val pattern: Regex) {
    companion object {
        /** Every topic whose whole name [pattern] matches. */
        fun matching(pattern: Regex): Topics = Topics(pattern)
    }
}

/** Where a subscription starts: [consume] here on any backend, and `subscribe` in surefold-kafka-pekko. */
object Kafka

/**
 * A run described that marks each record handled once its element reaches the end of the stream, for records
 * from [Kafka.consume]; the consumer commits them. It answers how many elements reached the end.
 */
fun <E> Stream<E, Committed<*>>.runCommitting(): Run<E, Long> =
    // Counted from Long rather than folded over Committed, which the guard beside runFold refuses.
    map { element ->
        element.handle?.handled()
        1L
    }.runFold(0L, Long::plus)

/** Each record's key and value decoded from bytes in the stream, for a module with a byte-level source. */
@KafkaSpi
fun <E, K, V> Stream<E, Committed<ConsumerRecord<ByteArray?, ByteArray?>>>.decodedWith(
    key: Decoder<K>,
    value: Decoder<V>,
): Stream<E, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    mapRecord { record -> record.decoded(key, value) }
