package dev.surefold.kafka

import dev.surefold.Carriers
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord

/**
 * [carried] on this record, one header per pair, in UTF-8 (spec 0123): what an event's append carried, a trace's
 * `traceparent` among it, sent on so the consumer continues the trace. The record is changed and handed back.
 */
fun <K, V> ProducerRecord<K, V>.carrying(carried: Map<String, String>): ProducerRecord<K, V> = also { record ->
    carried.forEach { (key, value) -> record.headers().add(key, value.toByteArray(Charsets.UTF_8)) }
}

/** What this record's producer carried: its headers, each read as UTF-8; the last of a name repeated wins. */
fun ConsumerRecord<*, *>.carried(): Map<String, String> =
    headers().filter { it.value() != null }.associate { it.key() to String(it.value(), Charsets.UTF_8) }

/**
 * [block] inside what this record carried, so what handling it opens or logs continues the producer's trace: one
 * record at a time. A batch is handled outside any, since its records may each come from a different trace.
 */
fun <A> ConsumerRecord<*, *>.within(block: () -> A): A = Carriers.within(carried(), block)
