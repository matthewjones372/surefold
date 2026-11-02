package dev.surefold.kafka.benchmarks

import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Topic
import dev.surefold.kafka.consume
import dev.surefold.kafka.mapParRecord
import dev.surefold.kafka.mapRecord
import dev.surefold.kafka.runCommitting
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerRecord
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

/** How many handler calls are in flight at once, for both parallel consumers alike. */
const val PARALLELISM = 64

private const val NANOS_PER_MICRO = 1_000L
private val POLL_TIMEOUT: Duration = Duration.ofMillis(100)

/** One record's handler: a blocking call of [micros] that does no work, the shape of a database write. */
// The stall is the point: it stands in for the handler's I/O, and the baseline was measured with it.
@Suppress("ForbiddenMethodCall")
fun handle(micros: Int) {
    val start = System.nanoTime()
    if (micros > 0) Thread.sleep(Duration.ofNanos(micros * NANOS_PER_MICRO))
    if (Stamps.on) Stamps.handled(start, System.nanoTime())
}

/**
 * The article's question measured rather than modelled: 5k records, each handled by a blocking call of
 * [ioMicros], committing as it goes. The serial pair are the poll loop and surefold's `mapRecord`; the parallel pair
 * are the hand-written loop that fans each poll out and waits for it, and surefold's `mapParRecord`, which keeps
 * polling while bodies are in flight. Both parallel consumers hold at most [PARALLELISM] calls at once.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class ProcessingBenchmarks {

    @Param("0", "100", "1000")
    var ioMicros: Int = 0

    private val forks = Forks()

    private val threads = Executors.newVirtualThreadPerTaskExecutor()

    private fun javaLoop(broker: Broker, batch: (List<ConsumerRecord<ByteArray, ByteArray>>) -> Unit) {
        KafkaConsumer(broker.consumer(), ByteArrayDeserializer(), ByteArrayDeserializer()).use { consumer ->
            consumer.subscribe(listOf(SLOW_TOPIC))
            var count = 0L
            while (count < SLOW_RECORDS) {
                val records = consumer.poll(POLL_TIMEOUT).toList()
                batch(records)
                consumer.commitSync()
                count += records.size
            }
            check(count, SLOW_RECORDS)
        }
    }

    /** The plain poll loop: each record handled in turn, then the batch committed. */
    @Benchmark
    fun javaSerial(broker: Broker) = javaLoop(broker) { records -> records.forEach { handle(ioMicros) } }

    /** The loop most services grow into: each poll's records fanned out to virtual threads, then waited for. */
    @Benchmark
    fun javaParallelBatch(broker: Broker) {
        val permits = Semaphore(PARALLELISM)
        javaLoop(broker) { records ->
            records.map {
                permits.acquire()
                threads.submit {
                    try {
                        handle(ioMicros)
                    } finally {
                        permits.release()
                    }
                }
            }.forEach { it.get() }
        }
    }

    private fun surefoldSource(broker: Broker) = Kafka.consume(
        broker.consumer(),
        Topic(SLOW_TOPIC),
        key = ByteArrayDeserializer(),
        value = ByteArrayDeserializer(),
    )

    @Benchmark
    fun surefoldSerial(broker: Broker) {
        val handled = surefoldSource(broker).mapRecord { handle(ioMicros) }
        check(handled.take(SLOW_RECORDS.toLong()).runCommitting().run(forks).done(), SLOW_RECORDS)
    }

    @Benchmark
    fun surefoldMapPar(broker: Broker) {
        val handled = surefoldSource(broker).mapParRecord(PARALLELISM) { handle(ioMicros) }
        check(handled.take(SLOW_RECORDS.toLong()).runCommitting().run(forks).done(), SLOW_RECORDS)
    }
}
