package io.github.matthewjones372.surefold.kafka

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import dev.surefold.metricTags
import dev.surefold.metrics
import org.apache.kafka.clients.consumer.Consumer
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.RecordMetadata
import org.apache.kafka.common.TopicPartition
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.LongAdder

// A duration is recorded in seconds, as surefold's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0

/**
 * Where a consumer's meters go, and the caller's tags: taken where the consumer is described, as `Measured` takes
 * them, since a backend such as Pekko opens it on a thread that inherited neither.
 */
internal class Measuring(private val metrics: Metrics, private val tags: Map<String, String>) {

    /** One run's meters, tagged with its group and client as [properties] name them; empty where they name none. */
    fun consumer(properties: Map<String, Any>): ConsumerMeters = ConsumerMeters(
        metrics,
        tags + mapOf(
            "group" to properties[ConsumerConfig.GROUP_ID_CONFIG]?.toString().orEmpty(),
            "client" to properties[ConsumerConfig.CLIENT_ID_CONFIG]?.toString().orEmpty(),
        ),
    )

    companion object {
        /** The metrics and tags in scope here. */
        fun here(): Measuring = Measuring(metrics.get(), metricTags.get())
    }
}

/**
 * A consumer's own meters, `surefold.kafka.consumer.*` (spec 0163). Those of the consumer as a whole are looked up
 * once; those of a partition at each poll that returns some of it, which a backend answers from its own map.
 */
internal class ConsumerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {

    private val poll: Histogram = metrics.histogram("$PREFIX.poll", tags)
    private val commit: Histogram = metrics.histogram("$PREFIX.commit", tags)
    private val close: Histogram = metrics.histogram("$PREFIX.close", tags)
    private val commitFailures: Counter = metrics.counter("$PREFIX.commit.failures", tags)
    private val assigned: Gauge = metrics.gauge("$PREFIX.assigned", tags)
    private val paused: Gauge = metrics.gauge("$PREFIX.paused", tags)
    private val inFlight: Gauge = metrics.gauge("$PREFIX.in.flight", tags)

    /** Records handed to the stream and those it has handled, the two counted on different threads. */
    private val handed = LongAdder()
    private val handled = LongAdder()

    /** A partition's own meters, looked up once each: a latency is recorded for every record handled. */
    private val lags = ConcurrentHashMap<TopicPartition, Gauge>()
    private val latencies = ConcurrentHashMap<TopicPartition, Histogram>()

    /** What a poll begun at [started] returned, per partition, and how long it took. */
    fun polled(records: ConsumerRecords<*, *>, started: Long) {
        poll.record(secondsSince(started))
        records.partitions().forEach { partition ->
            val of = tagsOf(partition)
            val returned = records.records(partition)
            metrics.counter("$PREFIX.records", of).increment(returned.size.toDouble())
            val bytes = returned.sumOf { maxOf(it.serializedKeySize(), 0) + maxOf(it.serializedValueSize(), 0) }
            metrics.counter("$PREFIX.bytes", of).increment(bytes.toDouble())
        }
        inFlight()
    }

    /**
     * How far behind its partitions' ends [consumer] is, after a poll: the client's own reckoning from the last fetch,
     * which costs no request, and none for a partition it has fetched nothing from yet.
     */
    fun lagged(consumer: Consumer<*, *>) = consumer.assignment().forEach { partition ->
        val lag = consumer.currentLag(partition)
        if (lag.isPresent) lagOf(partition).set(lag.asLong.toDouble())
    }

    /** [partitions] this consumer no longer owns lag nothing here: their new owner reports them. */
    fun left(partitions: Collection<TopicPartition>) = partitions.forEach { partition -> lagOf(partition).set(0.0) }

    /**
     * [record] handed to the stream, and what marks it handled: how stale it was then, from its timestamp, whichever
     * of create or append time its topic records.
     */
    fun handing(record: ConsumerRecord<*, *>): Handling {
        handed.increment()
        inFlight()
        val latency = latencies.computeIfAbsent(TopicPartition(record.topic(), record.partition())) { partition ->
            metrics.histogram("$PREFIX.latency", tagsOf(partition))
        }
        val written = record.timestamp()
        return Handling {
            handled.increment()
            inFlight()
            if (written >= 0) latency.record(maxOf(System.currentTimeMillis() - written, 0) / MILLIS_PER_SECOND)
        }
    }

    private fun lagOf(partition: TopicPartition): Gauge =
        lags.computeIfAbsent(partition) { metrics.gauge("$PREFIX.lag", tagsOf(it)) }

    private fun tagsOf(partition: TopicPartition): Map<String, String> =
        tags + mapOf("topic" to partition.topic(), "partition" to partition.partition().toString())

    /** Records handed to the stream and not yet handled, as each is handed or handled. */
    private fun inFlight() = inFlight.set((handed.sum() - handled.sum()).toDouble())

    /** A commit that waited for the broker, begun at [started], however it went. */
    fun committed(started: Long) = commit.record(secondsSince(started))

    fun commitFailed() = commitFailures.increment(1.0)

    fun closed(started: Long) {
        close.record(secondsSince(started))
        inFlight()
        paused.set(0.0)
    }

    /** A rebalance of [kind], assigned or revoked, that left this consumer owning [owned] partitions. */
    fun rebalanced(kind: String, owned: Int) {
        metrics.counter("$PREFIX.rebalances", tags + ("kind" to kind)).increment(1.0)
        assigned.set(owned.toDouble())
    }

    fun paused(partitions: Int) = paused.set(partitions.toDouble())

    companion object {
        private const val PREFIX = "surefold.kafka.consumer"

        /** Meters nobody reads: a loop that records nothing, such as one over assigned partitions, for now. */
        fun none(): ConsumerMeters = ConsumerMeters(NoMetrics, emptyMap())
    }
}

/** What marks one record handled in a consumer's meters. */
internal fun interface Handling {
    fun handled()
}

/**
 * A producer's own meters, `surefold.kafka.producer.*`, `surefold.kafka.dead.letters` and
 * `surefold.kafka.transactions.*` (spec 0163), tagged with its client and, for a record, its topic. A producer is
 * opened once and shared, so its meters go where they were in scope when it was opened; a topic's are looked up once.
 */
internal class ProducerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {

    private val topics = ConcurrentHashMap<String, Sent>()
    private val transactions: Histogram = metrics.histogram("surefold.kafka.transactions", tags)
    private val aborts: Counter = metrics.counter("surefold.kafka.transactions.aborts", tags)

    /** A send begun at [started] to [topic], acknowledged with [metadata]'s sizes or given up on. */
    fun sent(topic: String, started: Long, metadata: RecordMetadata?) {
        val sent = topics.computeIfAbsent(topic) { Sent(metrics, tags + ("topic" to it)) }
        if (metadata == null) {
            sent.failures.increment(1.0)
        } else {
            sent.records.increment(1.0)
            val bytes = maxOf(metadata.serializedKeySize(), 0) + maxOf(metadata.serializedValueSize(), 0)
            sent.bytes.increment(bytes.toDouble())
            sent.ack.record(secondsSince(started))
        }
    }

    /** A record from [topic] written to its dead letters. */
    fun deadLetter(topic: String) =
        metrics.counter("surefold.kafka.dead.letters", tags + ("topic" to topic)).increment(1.0)

    fun transacted(started: Long) = transactions.record(secondsSince(started))

    fun aborted() = aborts.increment(1.0)

    private class Sent(metrics: Metrics, tags: Map<String, String>) {
        val records: Counter = metrics.counter("surefold.kafka.producer.records", tags)
        val bytes: Counter = metrics.counter("surefold.kafka.producer.bytes", tags)
        val failures: Counter = metrics.counter("surefold.kafka.producer.failures", tags)
        val ack: Histogram = metrics.histogram("surefold.kafka.producer.ack", tags)
    }

    companion object {
        /** The meters in scope here, tagged with the client [properties] name, or none. */
        fun here(properties: Map<String, Any>): ProducerMeters = ProducerMeters(
            metrics.get(),
            metricTags.get() + ("client" to properties[ProducerConfig.CLIENT_ID_CONFIG]?.toString().orEmpty()),
        )
    }
}

private fun secondsSince(started: Long): Double = (System.nanoTime() - started) / NANOS_PER_SECOND
