package dev.surefold.kafka.benchmarks

import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Topic
import dev.surefold.kafka.consume
import dev.surefold.kafka.mapParPartitioned
import dev.surefold.kafka.mapParRecord
import dev.surefold.kafka.runCommitting
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Param
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import java.time.Duration
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** How long each record's handler takes on the skew rows, as `ProcessingBenchmarks`' slowest. */
private const val SKEW_IO_MICROS = 1000

/** The partition [SkewBenchmarks.skew] slows down. */
private const val SLOW_PARTITION = 0

/** Small enough that each poll carries a little of every partition: ~30 of the topic's 512-byte records. */
private const val INTERLEAVED_FETCH_BYTES = 16 * 1024

private val SKEW_POLL_TIMEOUT: Duration = Duration.ofMillis(100)

/**
 * `ProcessingBenchmarks`' parallel pair when one partition is slow, as when one tenant's writes are (spec 0156):
 * [skew] times longer on partition 0. A consumer that keeps every partition's order behind the slowest record
 * waits on it only where a poll carries several partitions at once, so [fetch] is either the default, where each
 * partition comes back whole, or [INTERLEAVED_FETCH_BYTES] a partition per fetch, a topic with steady traffic.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class SkewBenchmarks {

    @Param("1", "10")
    var skew: Int = 1

    @Param("whole", "interleaved")
    var fetch: String = "whole"

    private val forks = Forks()

    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    private fun micros(partition: Int) = if (partition == SLOW_PARTITION) SKEW_IO_MICROS * skew else SKEW_IO_MICROS

    private fun properties(broker: Broker): Map<String, Any> =
        if (fetch == "interleaved") {
            broker.consumer() + (ConsumerConfig.MAX_PARTITION_FETCH_BYTES_CONFIG to INTERLEAVED_FETCH_BYTES)
        } else {
            broker.consumer()
        }

    /** `javaParallelBatch`: each poll's records fanned out to virtual threads, waited for, then committed. */
    @Benchmark
    fun javaParallelBatch(broker: Broker) {
        val permits = Semaphore(PARALLELISM)
        KafkaConsumer(properties(broker), ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(SLOW_TOPIC))
            var count = 0L
            while (count < SLOW_RECORDS) {
                val records = consumer.poll(SKEW_POLL_TIMEOUT).toList()
                records.map { record ->
                    permits.acquire()
                    threads.submit {
                        try {
                            handle(micros(record.partition()))
                        } finally {
                            permits.release()
                        }
                    }
                }.forEach { it.get() }
                consumer.commitSync()
                count += records.size
            }
            check(count, SLOW_RECORDS)
        }
    }

    private fun surefoldSource(broker: Broker) = Kafka.consume(
        properties(broker),
        Topic(SLOW_TOPIC),
        key = ByteArrayDeserializer(),
        value = ByteArrayDeserializer(),
    )

    /** `surefoldMapPar`: `mapParRecord(64)`, every result passed on in the order it was read. */
    @Benchmark
    fun surefoldMapPar(broker: Broker) {
        val handled = surefoldSource(broker).mapParRecord(PARALLELISM) { record -> handle(micros(record.partition())) }
        check(handled.take(SLOW_RECORDS.toLong()).runCommitting().run(forks).done(), SLOW_RECORDS)
    }

    /** `mapParPartitioned(64)`: each result waits only behind its own partition's (spec 0156). */
    @Benchmark
    fun surefoldMapParPartitioned(broker: Broker) {
        val handled =
            surefoldSource(broker).mapParPartitioned(PARALLELISM) { record -> handle(micros(record.partition())) }
        check(handled.take(SLOW_RECORDS.toLong()).runCommitting().run(forks).done(), SLOW_RECORDS)
    }
}
