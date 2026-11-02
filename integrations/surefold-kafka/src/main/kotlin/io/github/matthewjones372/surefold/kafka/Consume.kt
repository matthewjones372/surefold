@file:OptIn(KafkaSpi::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Either
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.blocking
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRebalanceListener
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import java.util.concurrent.atomic.AtomicLong
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.toJavaDuration

/**
 * Every record on [topics] for the group [properties] name, from one `KafkaConsumer` per run, on whichever
 * backend runs it. End it on [runCommitting]; the consumer commits each record it was told is handled.
 *
 * Only the thread that polls touches the consumer: it commits what was handled before each poll, for the
 * partitions it loses in a rebalance, and when the run ends. A `stop()` wakes a poll that is waiting.
 * `enable.auto.commit` is always off.
 *
 * [questions], when given, answers what the run is asked while it runs (spec 0160).
 *
 * [onAssigned] and [onRevoked] hear of the partitions each rebalance gives and takes, on the polling thread, after the
 * consumer's own handling of them: a revoke has committed what it could first. What either throws ends the run, as a
 * defect (spec 0159).
 *
 * Each partition it is assigned starts where [startAt] says: by default after what the group committed (spec 0159).
 * A rebalance that takes a partition away commits what was handled on it; with [Rebalance.waitFor] it first waits
 * for what was handed out on it to be handled, so the partition's new owner reads none of it again (spec 0158).
 *
 * Each run records `surefold.kafka.consumer.*` (spec 0163) to the metrics in scope here, with the tags bound here.
 */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    vararg topics: Topic,
    key: Deserializer<K>,
    value: Deserializer<V>,
    pollTimeout: Duration = 100.milliseconds,
    rebalance: Rebalance = Rebalance.immediately,
    startAt: StartAt = StartAt.Committed,
    onAssigned: (Set<TopicPartition>) -> Unit = {},
    onRevoked: (Set<TopicPartition>) -> Unit = {},
    questions: ConsumerQuestions? = null,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    val names = topics.map { it.name }
    val hooks = Hooks(onAssigned, onRevoked, questions)
    return consuming(properties, key, value, pollTimeout, rebalance, startAt, hooks) { consumer, listener ->
        consumer.subscribe(names, listener)
    }
}

/** [consume] over every topic [topics] matches, those created while it runs as well (spec 0159). */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    topics: Topics,
    key: Deserializer<K>,
    value: Deserializer<V>,
    pollTimeout: Duration = 100.milliseconds,
    rebalance: Rebalance = Rebalance.immediately,
    startAt: StartAt = StartAt.Committed,
    onAssigned: (Set<TopicPartition>) -> Unit = {},
    onRevoked: (Set<TopicPartition>) -> Unit = {},
    questions: ConsumerQuestions? = null,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    val hooks = Hooks(onAssigned, onRevoked, questions)
    return consuming(properties, key, value, pollTimeout, rebalance, startAt, hooks) { consumer, listener ->
        consumer.subscribe(topics.pattern.toPattern(), listener)
    }
}

private fun <K, V> consuming(
    properties: Map<String, Any>,
    key: Deserializer<K>,
    value: Deserializer<V>,
    pollTimeout: Duration,
    rebalance: Rebalance,
    startAt: StartAt,
    hooks: Hooks,
    subscribe: (KafkaConsumer<K, V>, ConsumerRebalanceListener) -> Unit,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> {
    val measuring = Measuring.here()
    return Stream.blocking(
        open = {
            val named = properties.named()
            Loop(named, subscribe, key, value, pollTimeout, rebalance, startAt, hooks, measuring.consumer(named))
        },
        next = { loop -> loop.next() },
        ready = { loop -> loop.ready() },
        wake = { loop -> loop.wake() },
        close = { loop -> loop.close() },
    )
}

/**
 * [consume] over bytes, each key and value decoded in the stream: a record that cannot be read is a `Left`
 * that still carries its offset, so it is routed rather than ending the consumer.
 */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    vararg topics: Topic,
    key: Decoder<K>,
    value: Decoder<V>,
    pollTimeout: Duration = 100.milliseconds,
    rebalance: Rebalance = Rebalance.immediately,
    startAt: StartAt = StartAt.Committed,
    onAssigned: (Set<TopicPartition>) -> Unit = {},
    onRevoked: (Set<TopicPartition>) -> Unit = {},
    questions: ConsumerQuestions? = null,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    consume(
        properties,
        *topics,
        key = ByteArrayDeserializer(),
        value = ByteArrayDeserializer(),
        pollTimeout = pollTimeout,
        rebalance = rebalance,
        startAt = startAt,
        onAssigned = onAssigned,
        onRevoked = onRevoked,
        questions = questions,
    )
        .decodedWith(key, value)

/** [consume] over bytes on every topic [topics] matches, each key and value decoded in the stream. */
fun <K, V> Kafka.consume(
    properties: Map<String, Any>,
    topics: Topics,
    key: Decoder<K>,
    value: Decoder<V>,
    pollTimeout: Duration = 100.milliseconds,
    rebalance: Rebalance = Rebalance.immediately,
    startAt: StartAt = StartAt.Committed,
    onAssigned: (Set<TopicPartition>) -> Unit = {},
    onRevoked: (Set<TopicPartition>) -> Unit = {},
    questions: ConsumerQuestions? = null,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    consume(
        properties,
        topics,
        key = ByteArrayDeserializer(),
        value = ByteArrayDeserializer(),
        pollTimeout = pollTimeout,
        rebalance = rebalance,
        startAt = startAt,
        onAssigned = onAssigned,
        onRevoked = onRevoked,
        questions = questions,
    )
        .decodedWith(key, value)

/** What a run's caller hears of its rebalances (spec 0159). */
private class Hooks(
    val onAssigned: (Set<TopicPartition>) -> Unit,
    val onRevoked: (Set<TopicPartition>) -> Unit,
    val questions: ConsumerQuestions?,
)

/** How many consumers this process has named, as the client numbers the ones it names itself. */
private val named = AtomicLong()

/**
 * [this], with a `client.id` of its own where it names none: two consumers of one group in one process would
 * otherwise share their meters, each overwriting the other's gauges (spec 0163). Named as the client names its own.
 */
private fun Map<String, Any>.named(): Map<String, Any> =
    if (ConsumerConfig.CLIENT_ID_CONFIG in this) {
        this
    } else {
        val group = this[ConsumerConfig.GROUP_ID_CONFIG]?.let { "$it-" }.orEmpty()
        this + (ConsumerConfig.CLIENT_ID_CONFIG to "surefold-consumer-$group${named.incrementAndGet()}")
    }

/** One run's consumer of a group's partitions, committing what was handled through [commits]. */
private class Loop<K, V>(
    properties: Map<String, Any>,
    subscribe: (KafkaConsumer<K, V>, ConsumerRebalanceListener) -> Unit,
    key: Deserializer<K>,
    value: Deserializer<V>,
    private val pollTimeout: Duration,
    private val rebalance: Rebalance,
    private val startAt: StartAt,
    private val hooks: Hooks,
    private val meters: ConsumerMeters,
) : ConsumerRebalanceListener {

    private val consumer = KafkaConsumer(properties + (ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false), key, value)

    private val commits = Commits(consumer, meters = meters)

    /** The partitions this consumer owns, as its rebalances have left them: only touched inside a poll or close. */
    private val owned = mutableSetOf<TopicPartition>()

    /** What the last polls returned and the stream has yet to take: the keeper's poll can add to it. */
    private val polled = ArrayDeque<ConsumerRecord<K, V>>()

    // Before the keeper starts, so a subscription the client refuses leaves no keeper running.
    init {
        subscribe(consumer, this)
    }

    private val keeper = Keeper(consumer, properties, meters) { records -> polled.addAll(records) }

    private val answering = object : Answering {
        override fun <T> answer(question: (KafkaConsumer<*, *>) -> T): T = keeper.asking { question(consumer) }
    }

    // A second run on the same questions is refused, and closes what it opened rather than leaving it running.
    init {
        try {
            hooks.questions?.held(answering)
        } catch (refused: IllegalStateException) {
            keeper.stop()
            consumer.close()
            throw refused
        }
    }

    /** The next record; a poll waiting on a quiet topic waits until one arrives or [wake] is called. */
    fun next(): Committed<ConsumerRecord<K, V>> = keeper.touching {
        while (polled.isEmpty()) {
            // Between polls: what the run is being asked is answered here (spec 0160).
            keeper.answer()
            commits.commitAsync()
            keeper.polling()
            val started = System.nanoTime()
            val records = consumer.poll(pollTimeout.toJavaDuration())
            meters.polled(records, started)
            meters.lagged(consumer)
            polled.addAll(records)
            commits.polled()
        }
        commits.carrying(polled.removeFirst())
    }

    /** Whether the last poll still holds a record, so [next] returns without polling. */
    fun ready() = keeper.touching { polled.isNotEmpty() }

    fun wake() = consumer.wakeup()

    fun close() {
        hooks.questions?.letGo(answering)
        keeper.stop()
        keeper.closing { commits.close() }
    }

    override fun onPartitionsRevoked(partitions: Collection<TopicPartition>) {
        // A take that ends a Forks run interrupts the fork polling here, often just as the last record it waits for
        // is handled: what was handled is committed all the same, and the interrupt set again after.
        var interrupted = Thread.interrupted()
        try {
            // Inside the callback, the one place Kafka lets a member hold a rebalance; the stream handles on meanwhile.
            interrupted = commits.handledOut(partitions.toSet(), rebalance) || interrupted
            commits.commit(partitions.toSet())
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
        commits.forget(partitions)
        // Records already polled from a partition this consumer has lost are its new owner's to read.
        val lost = partitions.toSet()
        polled.removeAll { TopicPartition(it.topic(), it.partition()) in lost }
        owned.removeAll(lost)
        meters.left(lost)
        meters.rebalanced("revoked", owned.size)
        hooks.onRevoked(lost)
    }

    override fun onPartitionsAssigned(partitions: Collection<TopicPartition>) {
        consumer.startAt(startAt, partitions.toSet())
        keeper.assigned(partitions)
        owned.addAll(partitions)
        meters.rebalanced("assigned", owned.size)
        hooks.onAssigned(partitions.toSet())
    }
}
