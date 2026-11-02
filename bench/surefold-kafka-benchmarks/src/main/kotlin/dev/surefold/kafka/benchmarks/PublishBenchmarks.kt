package dev.surefold.kafka.benchmarks

import dev.surefold.kafka.Kafka
import dev.surefold.kafka.Producer
import dev.surefold.kafka.Topic
import dev.surefold.kafka.producer
import dev.surefold.kafka.publishTo
import dev.surefold.kafka.record
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringSerializer
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

private const val PUBLISHED = 20_000
private const val IN_FLIGHT = 1_000
private const val VALUE_BYTES = 512
private const val PUBLISH_TOPIC = "published"
private const val SETUP_SECONDS = 30L

/**
 * [PUBLISHED] records of [VALUE_BYTES] sent to a topic of its own, at most [IN_FLIGHT] waiting on the broker at
 * once: surefold's `publishTo`, which is `mapAsync` over the producer's futures, against the Java producer bounded by
 * a `Semaphore` released in its callback. One producer per trial for each, so neither pays for connecting.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
open class PublishBenchmarks {

    private val forks = Forks()
    private val value = ByteArray(VALUE_BYTES) { (it % 26 + 'a'.code).toByte() }
    private lateinit var java: KafkaProducer<String, ByteArray>
    private lateinit var surefold: Producer<String, ByteArray>

    private fun settings(broker: Broker): Map<String, Any> =
        mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to broker.bootstrap)

    @Setup(Level.Trial)
    fun start(broker: Broker) {
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to broker.bootstrap)).use { admin ->
            admin.createTopics(listOf(NewTopic(PUBLISH_TOPIC, PARTITIONS, 1)))
                .all().get(SETUP_SECONDS, TimeUnit.SECONDS)
        }
        java = KafkaProducer(settings(broker), StringSerializer(), ByteArraySerializer())
        surefold = Kafka.producer(settings(broker), StringSerializer(), ByteArraySerializer())
    }

    @TearDown(Level.Trial)
    fun stop() {
        java.close()
        surefold.close()
    }

    @Benchmark
    fun javaProducer(): Int {
        val room = Semaphore(IN_FLIGHT)
        repeat(PUBLISHED) { i ->
            room.acquire()
            java.send(ProducerRecord(PUBLISH_TOPIC, "key$i", value)) { _, failed ->
                room.release()
                check(failed == null) { "send failed: $failed" }
            }
        }
        room.acquire(IN_FLIGHT)
        return PUBLISHED
    }

    @Benchmark
    fun surefoldPublishTo(): Long {
        val topic = Topic(PUBLISH_TOPIC)
        val sent = Stream.from(0 until PUBLISHED)
            .publishTo(surefold, IN_FLIGHT) { i -> topic.record("key$i", value) }
            .runFold(0L) { n, _ -> n + 1 }
            .run(forks)
            .done()
        check(sent, PUBLISHED)
        return sent
    }
}
