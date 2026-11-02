@file:OptIn(KafkaSpi::class)

package dev.surefold.kafka

import dev.surefold.logAnnotated
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata

/** For a module that brings records of its own into surefold-kafka, such as the Pekko connector; not for a pipeline. */
@RequiresOptIn("A seam for a module that feeds surefold-kafka records from a consumer of its own.")
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.CLASS, AnnotationTarget.PROPERTY, AnnotationTarget.CONSTRUCTOR, AnnotationTarget.FUNCTION)
annotation class KafkaSpi

/** An element and the offset it came with; the offset is committed by `runCommitting` and never handed out. */
class Committed<out A : Any> @KafkaSpi constructor(
    val value: A,
    @property:KafkaSpi val position: Position,
    // Null on every element but the last that one record expanded to, so the record commits once, after all of them.
    @property:KafkaSpi val handle: Handle?,
)

/** Where a record was read from: what every line a body writes is annotated with. */
@KafkaSpi
data class Position(val topic: String, val partition: Int, val offset: Long)

/** How a record's offset is committed, which depends on the consumer that read it. */
@KafkaSpi
interface Handle {

    /** The record is done with: [Kafka.consume]'s loop commits it before its next poll. */
    fun handled()

    /**
     * [send] given the consumer group as it is for this record, for a transaction that commits its offset: null
     * from a source that cannot hand it over, which a transactional run refuses. [send] runs where the group cannot
     * change under it, so the offsets it sends reach the broker with the group they were sent with.
     */
    fun <T> withGroup(send: (ConsumerGroupMetadata?) -> T): T = send(null)
}

/**
 * The handle of a batch from [groupedRecord], which marks each of its [records] handled with its source's own
 * handle: what a module that commits them unpacks. A record still expanding has no handle, and commits later.
 */
@KafkaSpi
class Batched(records: List<Committed<*>>) : Handle {
    val records: List<Committed<*>> = records.flatMap { it.records() }.filter { it.handle != null }

    override fun handled() = records.forEach { it.handle?.handled() }

    override fun <T> withGroup(send: (ConsumerGroupMetadata?) -> T): T =
        records.firstOrNull()?.handle?.withGroup(send) ?: send(null)
}

/** The records this element commits, each with its source's own handle: a batch's, or the element itself. */
@KafkaSpi
fun Committed<*>.records(): List<Committed<*>> = (handle as? Batched)?.records ?: listOf(this)

internal fun <B : Any> Committed<*>.carrying(value: B): Committed<B> = Committed(value, position, handle)

/** [f] on the value, with the record's position on every line it writes; a batch's lines carry none. */
internal fun <A : Any, B> Committed<A>.annotated(f: (A) -> B): B =
    if (handle is Batched) {
        f(value)
    } else {
        logAnnotated(
            "kafka.topic" to position.topic,
            "kafka.partition" to position.partition.toString(),
            "kafka.offset" to position.offset.toString(),
        ) { f(value) }
    }
