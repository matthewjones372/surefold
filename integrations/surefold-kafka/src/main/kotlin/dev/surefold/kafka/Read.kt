@file:OptIn(KafkaSpi::class)

package dev.surefold.kafka

import dev.surefold.fail.Either
import dev.surefold.stream.Stream
import dev.surefold.stream.blocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import java.time.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/** Where a [Kafka.read] starts in each of its partitions. */
sealed interface From {
    /** After the last offset the `group.id` committed; with none committed, where `auto.offset.reset` says. */
    data object Committed : From

    data object Earliest : From

    data object Latest : From

    data class Offset(val offset: Long) : From

    /** At the first record written at or after [time]; at the partition's end where none was (spec 0159). */
    data class Time(val time: Instant) : From
}

/** Where a [Kafka.read] ends. */
sealed interface Until {
    data object Never : Until

    /** At each partition's end offset as it was when the read opened: what was written before it, and no more. */
    data object EndAtStart : Until
}

/**
 * Every record of one [partition] of [topic], from [from] until [until], read by a consumer it is assigned to, so no
 * rebalance moves it. End it on [runCommitting]: with a `group.id` in [properties], each record is committed once it
 * is handled, as [Kafka.consume] does; without one, nothing is committed and [From.Committed] is refused here.
 */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partition: Int,
    key: Deserializer<K>,
    value: Deserializer<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> =
    read(properties, topic, listOf(partition), key, value, from, until, pollTimeout)

/**
 * Every record of [partitions] of [topic], read by one consumer they are all assigned to (spec 0159), each from
 * [from] until [until], as the one-partition [read] reads its own. The read ends once every partition has reached its
 * end; records of one partition keep their order, and the partitions interleave as the consumer polls them.
 */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partitions: List<Int>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    require(partitions.isNotEmpty()) { "Kafka.read of ${topic.name} names no partition to read" }
    val grouped = properties[ConsumerConfig.GROUP_ID_CONFIG] != null
    require(grouped || from != From.Committed) {
        "Kafka.read from the committed offset needs a group.id in its properties, and ${topic.name} was read with none"
    }
    val assigned = partitions.distinct().map { TopicPartition(topic.name, it) }
    return Stream.blocking(
        open = { Assigned(properties, assigned, key, value, from, until, pollTimeout) },
        next = { read -> read.next() },
        ready = { read -> read.ready() },
        wake = { read -> read.wake() },
        close = { read -> read.close() },
    )
}

/** [read] over bytes, each key and value decoded in the stream, as the [Decoder] form of [Kafka.consume] is. */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partition: Int,
    key: Decoder<K>,
    value: Decoder<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    read(properties, topic, partition, ByteArrayDeserializer(), ByteArrayDeserializer(), from, until, pollTimeout)
        .decodedWith(key, value)

/** [read] of many partitions over bytes, each key and value decoded in the stream. */
fun <K, V> Kafka.read(
    properties: Map<String, Any>,
    topic: Topic,
    partitions: List<Int>,
    key: Decoder<K>,
    value: Decoder<V>,
    from: From,
    until: Until,
    pollTimeout: Duration = 100.milliseconds,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    read(properties, topic, partitions, ByteArrayDeserializer(), ByteArrayDeserializer(), from, until, pollTimeout)
        .decodedWith(key, value)

/** One run's consumer of its assigned [partitions], which ends once each has reached its [ends], if it has them. */
private class Assigned<K, V>(
    properties: Map<String, Any>,
    private val partitions: List<TopicPartition>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    from: From,
    until: Until,
    private val pollTimeout: Duration,
) {
    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    private val commits = Commits(consumer, committing = properties[ConsumerConfig.GROUP_ID_CONFIG] != null)

    private var polled: Iterator<ConsumerRecord<K, V>> = emptyList<ConsumerRecord<K, V>>().iterator()

    private val ends: Map<TopicPartition, Long>?

    init {
        consumer.assign(partitions)
        when (from) {
            From.Committed -> consumer.committed(partitions.toSet()).forEach { (partition, committed) ->
                committed?.let { consumer.seek(partition, it) }
            }

            From.Earliest -> consumer.seekToBeginning(partitions)

            From.Latest -> consumer.seekToEnd(partitions)

            is From.Offset -> partitions.forEach { consumer.seek(it, from.offset) }

            is From.Time -> consumer.seekToTime(partitions, from.time)
        }
        ends = when (until) {
            Until.Never -> null
            Until.EndAtStart -> consumer.endOffsets(partitions)
        }
    }

    /** The next record, or `null` once every partition has reached its end; a read with no end waits for one. */
    fun next(): Committed<ConsumerRecord<K, V>>? {
        while (!polled.hasNext()) {
            if (ends != null) {
                // A partition at its end is read no further, so its records past the end are not fetched.
                val done = partitions.filter { consumer.position(it) >= ends.getValue(it) }
                if (done.size == partitions.size) return null
                consumer.pause(done)
            }
            commits.commitAsync()
            polled = consumer.poll(pollTimeout.toJavaDuration())
                .filter { record -> ends == null || record.offset() < ends.getValue(record.partitioned()) }
                .iterator()
            commits.polled()
        }
        return commits.carrying(polled.next())
    }

    /** Whether the last poll still holds a record, so [next] returns without polling. */
    fun ready() = polled.hasNext()

    fun wake() = consumer.wakeup()

    fun close() = commits.close()
}

private fun ConsumerRecord<*, *>.partitioned() = TopicPartition(topic(), partition())
