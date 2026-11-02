@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Either
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.onLeft
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.groupedWithin
import io.github.matthewjones372.surefold.stream.mapAsync
import io.github.matthewjones372.surefold.stream.mapConcat
import io.github.matthewjones372.surefold.stream.mapPar
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.Metric
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.PartitionInfo
import org.apache.kafka.common.serialization.Serializer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import org.apache.kafka.clients.producer.Producer as Client

/**
 * One `KafkaProducer`, shared by every run and every caller, which is how the client is meant to be used: it is
 * thread-safe, and it batches what many callers send. Whoever opens it closes it, which sends what is pending.
 *
 * A send the client can retry, it retries, for up to `delivery.timeout.ms`. What reaches surefold is final: a record
 * the broker acknowledged, or one the producer gave up on.
 */
class Producer<K, V> internal constructor(
    private val client: Client<K, V>,
    private val meters: ProducerMeters,
) : AutoCloseable {

    /** A producer over [client], recording to the meters in scope here (spec 0163). */
    @KafkaSpi
    constructor(client: Client<K, V>) : this(client, ProducerMeters.here(emptyMap()))

    /** The record handed to the producer; the stage completes once the broker acknowledges it. */
    fun send(record: ProducerRecord<K, V>): CompletableFuture<Published> {
        val acked = CompletableFuture<Published>()
        val started = System.nanoTime()
        // A serializer that throws does so here rather than in the callback, and a full buffer waits here too.
        Either.catch {
            client.send(record) { metadata, failed ->
                meters.sent(record.topic(), started, metadata.takeIf { failed == null })
                if (failed == null) acked.complete(metadata.published()) else acked.completeExceptionally(failed)
            }
        }.onLeft { thrown ->
            meters.sent(record.topic(), started, null)
            acked.completeExceptionally(thrown)
        }
        return acked
    }

    /** A record from [topic] written to its dead letters. */
    internal fun deadLetter(topic: String) = meters.deadLetter(topic)

    /** [send], waited on: `Left` for a record the producer gave up on. */
    fun publish(record: ProducerRecord<K, V>): Either<PublishFailed, Published> = send(record).outcome(record)

    /**
     * Each of [records] handed to the producer without waiting on the one before, then waited on: one outcome per
     * record, in order. Once one fails, in [send] or in its callback, the records not yet handed over are not sent,
     * and come back `PublishFailed` with a [PublishOmitted] cause. Those already handed over keep their own.
     */
    fun publishAll(records: List<ProducerRecord<K, V>>): List<Either<PublishFailed, Published>> {
        val first = AtomicReference<Throwable>()
        val sent = records.map { record ->
            first.get()?.let { failed -> CompletableFuture.failedFuture<Published>(PublishOmitted(failed)) }
                ?: send(record).also { acked -> acked.whenComplete { _, failed -> first.compareAndSet(null, failed) } }
        }
        return records.zip(sent) { record, acked -> acked.outcome(record) }
    }

    /**
     * Every record handed over so far sent at once, whatever `linger.ms` says, and waited on until each is acknowledged
     * or given up on (spec 0160): before a checkpoint, or a test that reads back what it wrote.
     */
    fun flush() = client.flush()

    /** The partitions of [topic], in order, as this producer's metadata knows them, each with its leader. */
    fun partitionsFor(topic: Topic): List<PartitionInfo> = client.partitionsFor(topic.name).sortedBy { it.partition() }

    /** The client's own metrics, all of them (spec 0160): spec 0163's meters record a few, and this has the rest. */
    fun metrics(): Map<MetricName, Metric> = client.metrics()

    override fun close() = client.close()
}

/** A producer from [properties], each record's key and value written by [key] and [value]. */
fun <K, V> Kafka.producer(properties: Map<String, Any>, key: Serializer<K>, value: Serializer<V>): Producer<K, V> =
    Producer(KafkaProducer(properties, key, value), ProducerMeters.here(properties))

/** Where the broker put a record. */
data class Published(val topic: String, val partition: Int, val offset: Long)

/** A record the producer gave up on, and why: too large, not authorised, or not acknowledged in time. */
class PublishFailed internal constructor(val record: ProducerRecord<*, *>, val cause: Throwable) {
    override fun toString(): String = "PublishFailed(${record.topic()}: $cause)"
}

/** A record [Producer.publishAll] did not send, because one before it in the batch failed with [cause]. */
class PublishOmitted internal constructor(cause: Throwable) :
    Exception("not sent: a record before it in the batch failed", cause)

/** A record for this topic, so a sink's arguments read as the topic they write to. */
fun <K, V> Topic.record(key: K, value: V): ProducerRecord<K, V> = ProducerRecord(name, key, value)

/**
 * Each element sent as the record [to] makes of it, and passed on, in order, once the broker has it. Up to
 * [inFlight] wait on the broker at once, so the producer can batch them. A record the producer gives up on is a
 * defect, for `restartOnDefect`.
 */
fun <E, A : Any, K, V> Stream<E, A>.publishTo(
    producer: Producer<K, V>,
    inFlight: Int = DEFAULT_IN_FLIGHT,
    to: (A) -> ProducerRecord<K, V>,
): Stream<E, A> =
    mapAsync(inFlight) { element -> producer.send(to(element)).thenApply { element } }

/**
 * [publishTo] for a consumed record: its offset moves on only once what it produced is acknowledged, and every
 * record before it has been too. A stop or a defect leaves the rest uncommitted, and they are sent again.
 */
fun <E, A : Any, K, V> Stream<E, Committed<A>>.publishRecord(
    producer: Producer<K, V>,
    inFlight: Int = DEFAULT_IN_FLIGHT,
    to: (A) -> ProducerRecord<K, V>,
): Stream<E, Committed<A>> =
    mapAsync(inFlight) { c: Committed<A> -> producer.send(c.annotated(to)).thenApply { c } }

/**
 * [publishRecord] in batches of up to [batch], or what came within [within], one at a time through
 * [Producer.publishAll], with each record passed on as the outcome of its own write: `Left` for one the producer
 * gave up on, and for the rest of its batch, which is not sent. Nothing here fails the stream; what comes after
 * decides. `absolve()` ends the run with what came before the `Left` committed, `divertLefts` goes on past it.
 */
fun <E, A : Any, K, V> Stream<E, Committed<A>>.publishBatch(
    producer: Producer<K, V>,
    batch: Int = DEFAULT_BATCH,
    within: Duration = DEFAULT_WITHIN,
    to: (A) -> ProducerRecord<K, V>,
): Stream<E, Committed<Either<PublishFailed, A>>> =
    groupedWithin(batch, within)
        // One batch at a time, off the stream's own thread, because publishAll blocks.
        .mapPar(1) { taken: List<Committed<A>> -> taken.zip(producer.publishAll(taken.map { c -> c.annotated(to) })) }
        .mapConcat { sent -> sent.map { (c, outcome) -> c.carrying(outcome.map { c.value }) } }

/**
 * Each record that could not be read, written to [topic] as it was read, for `divertLefts`: its key, value and
 * headers, and headers saying where it came from and why. It returns once the broker has it, so the bad record's
 * offset moves on only then; a dead letter the producer gives up on is thrown, a defect.
 */
fun Producer<ByteArray?, ByteArray?>.deadLetters(topic: Topic): (DecodeError) -> Unit = { error ->
    val letter = ProducerRecord(topic.name, null, error.key, error.value, error.headers)
    mapOf(
        "topic" to error.topic,
        "partition" to error.partition.toString(),
        "offset" to error.offset.toString(),
        "part" to error.part.name,
        "cause" to error.cause.toString(),
    ).forEach { (name, value) -> letter.headers().add("$DEAD_LETTER_HEADER.$name", value.toByteArray()) }
    publish(letter).onLeft { failed -> throw failed.cause }
    deadLetter(error.topic)
}

/** Enough in flight for the producer to fill its batches, few enough that a stop leaves little to send again. */
const val DEFAULT_IN_FLIGHT: Int = 256

/** What every header a dead letter gains is named under. */
const val DEAD_LETTER_HEADER: String = "surefold.dead-letter"

private fun RecordMetadata.published() = Published(topic(), partition(), offset())

private fun CompletableFuture<Published>.outcome(record: ProducerRecord<*, *>): Either<PublishFailed, Published> =
    Either.catch { get() }.mapLeft { thrown -> PublishFailed(record, (thrown as? ExecutionException)?.cause ?: thrown) }
