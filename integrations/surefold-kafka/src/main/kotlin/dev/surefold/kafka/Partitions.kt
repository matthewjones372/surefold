package dev.surefold.kafka

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer

/**
 * The partitions of [topic], in order, from a consumer built on [properties] and closed before this returns: what a
 * caller that reads each partition on its own plans from. It joins no group and commits nothing.
 */
fun Kafka.partitions(properties: Map<String, Any>, topic: Topic): List<Int> =
    KafkaConsumer(properties, ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
        consumer.partitionsFor(topic.name).map { it.partition() }.sorted()
    }
