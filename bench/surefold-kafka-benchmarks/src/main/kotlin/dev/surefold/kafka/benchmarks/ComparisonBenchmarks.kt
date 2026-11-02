package dev.surefold.kafka.benchmarks

import dev.surefold.capturingMetrics
import dev.surefold.kafka.From
import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Topic
import dev.surefold.kafka.Until
import dev.surefold.kafka.consume
import dev.surefold.kafka.read
import dev.surefold.kafka.runCommitting
import dev.surefold.kafka.subscribe
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.mergeAll
import dev.surefold.stream.run
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

private val POLL_TIMEOUT: Duration = Duration.ofMillis(100)

/** A run that did not end `Done` would be measuring a different pipeline, so it stops the benchmark. */
internal fun <E, R> CompletionStage<Exit<E, R>>.done(): R =
    when (val exit = toCompletableFuture().join()) {
        is Exit.Done -> exit.value
        is Exit.Failed -> error("the benchmark pipeline failed: ${exit.error}")
        is Exit.Died -> throw exit.cause
    }

internal fun check(count: Long, expected: Int) =
    check(count == expected.toLong()) { "consumed $count records instead of $expected" }

/**
 * zio-kafka's `comparison` benchmarks, with surefold in zio-kafka's place: each invocation consumes the topic's
 * 50k records and counts them. `kafkaClients` and `manualKafkaClients` are zio-kafka's own, ported line for
 * line: one consumer made once per trial, polled in a tight loop, nothing committed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ComparisonBenchmarks {

    private lateinit var shared: KafkaConsumer<ByteArray, ByteArray>

    private lateinit var system: ActorSystem

    private val forks = Forks()

    private val partitions = (0 until PARTITIONS).map { TopicPartition(TOPIC, it) }

    @Setup(Level.Trial)
    fun open(broker: Broker) {
        shared = KafkaConsumer(broker.consumer("shared"), ByteArrayDeserializer(), ByteArrayDeserializer())
        system = ActorSystem.create("surefold-kafka-benchmarks")
    }

    @TearDown(Level.Trial)
    fun close() {
        shared.close()
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }

    private fun KafkaConsumer<ByteArray, ByteArray>.drain(commit: Boolean = false): Long {
        var count = 0L
        while (count < RECORDS) {
            if (commit) commitSync()
            count += poll(POLL_TIMEOUT).count()
        }
        return count
    }

    /** zio-kafka's `kafkaClients`: subscribe, poll until 50k, unsubscribe. */
    @Benchmark
    fun kafkaClients() {
        shared.subscribe(listOf(TOPIC))
        val count = shared.drain()
        shared.unsubscribe()
        check(count, RECORDS)
    }

    /** zio-kafka's `manualKafkaClients`: the same with the six partitions assigned. */
    @Benchmark
    fun manualKafkaClients() {
        shared.assign(partitions)
        val count = shared.drain()
        shared.unsubscribe()
        check(count, RECORDS)
    }

    /** What a surefold run pays for and the loop above does not: a consumer opened, joined and closed per run. */
    @Benchmark
    fun kafkaClientsFreshConsumer(broker: Broker) {
        KafkaConsumer(broker.consumer(), ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            check(consumer.drain(), RECORDS)
        }
    }

    /** The same, committing before each poll and on close, as surefold's consumer does: the fair hand-written twin. */
    @Benchmark
    fun kafkaClientsCommitting(broker: Broker) {
        KafkaConsumer(broker.consumer(), ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(TOPIC))
            check(consumer.drain(commit = true), RECORDS)
            consumer.commitSync()
        }
    }

    private fun surefoldConsume(broker: Broker) =
        Kafka.consume(broker.consumer(), Topic(TOPIC), key = ByteArrayDeserializer(), value = ByteArrayDeserializer())
            .take(RECORDS.toLong())
            .runCommitting()

    /** `Kafka.consume` on Forks: one virtual thread polls, carries each record to the end, and commits. */
    @Benchmark
    fun surefoldForks(broker: Broker) = check(surefoldConsume(broker).run(forks).done(), RECORDS)

    /**
     * [surefoldForks] recording its meters to an in-memory backend, which keeps every latency: what the meters cost
     * where something reads them. [surefoldForks] is the same run with no backend bound.
     */
    @Benchmark
    fun surefoldForksMetered(broker: Broker) =
        capturingMetrics { check(surefoldConsume(broker).run(forks).done(), RECORDS) }

    /** The same description on Pekko Streams. */
    @Benchmark
    fun surefoldPekko(broker: Broker) = check(surefoldConsume(broker).run(system).done(), RECORDS)

    /** zio-kafka's `manualZioKafka`: each partition assigned and read to its end, merged, nothing committed. */
    @Suppress("SpreadOperator") // Six sources, copied once a run: mergeAll is the call a reader would write.
    @Benchmark
    fun manualSurefoldForks(broker: Broker) {
        val properties = broker.consumer(group = null)
        val reads = partitions.map { p ->
            Kafka.read(
                properties,
                Topic(TOPIC),
                p.partition(),
                key = ByteArrayDeserializer(),
                value = ByteArrayDeserializer(),
                from = From.Earliest,
                until = Until.EndAtStart,
            )
        }
        val merged = reads.first().mergeAll(*reads.drop(1).toTypedArray())
        check(merged.runCommitting().run(forks).done(), RECORDS)
    }

    /** `Kafka.subscribe`, Pekko's own connector in surefold-kafka-pekko, with its committer sink. */
    @Benchmark
    fun surefoldPekkoConnector(broker: Broker) {
        val settings = ConsumerSettings.create(system, ByteArrayDeserializer(), ByteArrayDeserializer())
            .withProperties(broker.consumer().mapValues { (_, v) -> v.toString() })
            .withProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false")
            .withStopTimeout(Duration.ZERO)
        Kafka.subscribe(settings, Topic(TOPIC))
            .take(RECORDS.toLong())
            .runCommitting(CommitterSettings.create(system))
            .run(system)
            .done()
    }
}
