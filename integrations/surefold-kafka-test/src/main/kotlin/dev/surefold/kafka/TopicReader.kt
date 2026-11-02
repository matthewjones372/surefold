package dev.surefold.kafka

import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import java.time.Duration

/**
 * What a test reads of [topic] on the cluster at [bootstrap], by the deserializers the service uses (spec 0117): any
 * broker, embedded or in a container. It holds one consumer across calls until closed.
 */
fun <K, V> Kafka.reading(
    topic: Topic,
    bootstrap: String,
    key: Deserializer<K>,
    value: Deserializer<V>,
): TopicReader<K, V> = TopicReader(topic, bootstrap, key, value)

/**
 * A topic as a test sees it, read with no group: it assigns itself every partition and commits nothing, so reading
 * changes nothing a service's groups see.
 *
 * Each read is a snapshot. It takes each partition's end offset, then reads from the start up to it, so it answers
 * what the broker held when asked, at once for an empty topic, with no timeout of its own. A partition still being
 * written may have moved on by the time the read ends; a test waiting for a record asks again.
 */
class TopicReader<K, V> internal constructor(
    private val topic: Topic,
    bootstrap: String,
    private val key: Deserializer<K>,
    private val value: Deserializer<V>,
) : AutoCloseable {

    private val consumer = KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        ByteArrayDeserializer(),
        ByteArrayDeserializer(),
    )

    /**
     * Every record on the topic now, each partition oldest first, partitions in order. A record that cannot be
     * decoded throws [IllegalStateException] naming where it is, with the deserializer's throw as its cause: a test
     * that expects junk reads with a `ByteArrayDeserializer`.
     */
    fun records(): List<ConsumerRecord<K, V>> {
        val partitions = consumer.partitionsFor(topic.name).orEmpty()
            .map { TopicPartition(topic.name, it.partition()) }
            .sortedBy { it.partition() }
        consumer.assign(partitions)
        consumer.seekToBeginning(partitions)
        val ends = consumer.endOffsets(partitions)
        val read = partitions.associateWith { mutableListOf<ConsumerRecord<ByteArray?, ByteArray?>>() }
        while (partitions.any { consumer.position(it) < ends.getValue(it) }) {
            consumer.poll(POLL).forEach { record ->
                val partition = TopicPartition(record.topic(), record.partition())
                if (record.offset() < ends.getValue(partition)) read.getValue(partition) += record
            }
        }
        return partitions.flatMap { read.getValue(it) }.map { it.decoded() }
    }

    /** Every value on the topic now, as [records] reads them. */
    fun values(): List<V> = records().map { it.value() }

    /** Every key on the topic now, as [records] reads them. */
    fun keys(): List<K> = records().map { it.key() }

    override fun close() = consumer.close()

    private fun ConsumerRecord<ByteArray?, ByteArray?>.decoded(): ConsumerRecord<K, V> {
        fun <A> read(deserializer: Deserializer<A>, part: String, bytes: ByteArray?): A = try {
            deserializer.deserialize(topic(), headers(), bytes)
        } catch (@Suppress("TooGenericExceptionCaught") thrown: RuntimeException) {
            throw IllegalStateException("the $part at ${topic()}-${partition()}@${offset()} could not be read", thrown)
        }
        return ConsumerRecord(
            topic(),
            partition(),
            offset(),
            timestamp(),
            timestampType(),
            serializedKeySize(),
            serializedValueSize(),
            read(key, "key", key()),
            read(value, "value", value()),
            headers(),
            leaderEpoch(),
        )
    }

    private companion object {
        val POLL: Duration = Duration.ofMillis(100)
    }
}

/** What [group] has committed on [topic] at [bootstrap], summed over its partitions: 0 for none (spec 0117). */
fun committedOffset(bootstrap: String, group: String, topic: Topic): Long =
    committedOffsets(bootstrap, group, topic).values.sum()

/** What [group] has committed on each partition of [topic] it has committed on at all, at [bootstrap]. */
fun committedOffsets(bootstrap: String, group: String, topic: Topic): Map<Int, Long> =
    Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
        admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().get()
            .filter { (partition, at) -> partition.topic() == topic.name && at != null }
            .map { (partition, at) -> partition.partition() to at.offset() }
            .toMap()
    }
