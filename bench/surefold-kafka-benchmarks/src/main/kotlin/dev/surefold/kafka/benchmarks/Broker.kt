package dev.surefold.kafka.benchmarks

import io.github.embeddedkafka.EmbeddedK
import io.github.embeddedkafka.EmbeddedKafka
import io.github.embeddedkafka.EmbeddedKafkaConfig
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.StringSerializer
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.random.Random

/** zio-kafka's numbers: 50k records of ~512 bytes over six partitions. */
const val RECORDS = 50_000
const val PARTITIONS = 6
const val TOPIC = "topic1"

/** A smaller topic for the handlers that take time, so the serial loop finishes in seconds. */
const val SLOW_RECORDS = 5_000
const val SLOW_TOPIC = "topic2"

private const val VALUE_BYTES = 512
private const val MAX_POLL_RECORDS = 1000
private const val SETUP_SECONDS = 30L
private const val METADATA_MAX_AGE_MILLIS = 100
private const val SESSION_TIMEOUT_MILLIS = 3000
private const val HEARTBEAT_MILLIS = 1000

/** One broker per fork in the benchmark's JVM, with both topics written before the first iteration. */
@State(Scope.Benchmark)
open class Broker {

    private lateinit var kafka: EmbeddedK

    lateinit var bootstrap: String

    @Setup(Level.Trial)
    fun start() {
        kafka = startBroker()
        bootstrap = "localhost:${kafka.config().kafkaPort()}"
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            admin.createTopics(listOf(NewTopic(TOPIC, PARTITIONS, 1), NewTopic(SLOW_TOPIC, PARTITIONS, 1)))
                .all().get(SETUP_SECONDS, TimeUnit.SECONDS)
        }
        produce(TOPIC, RECORDS)
        produce(SLOW_TOPIC, SLOW_RECORDS)
    }

    @TearDown(Level.Trial)
    fun stop() {
        kafka.stop(true)
    }

    /** zio-kafka's test consumer settings, with a fresh group unless [group] names one. */
    fun consumer(group: String? = "group-${UUID.randomUUID()}"): Map<String, Any> = buildMap {
        put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap)
        put(ConsumerConfig.CLIENT_ID_CONFIG, "client-${UUID.randomUUID()}")
        put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
        put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false)
        put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, MAX_POLL_RECORDS)
        put(ConsumerConfig.METADATA_MAX_AGE_CONFIG, METADATA_MAX_AGE_MILLIS)
        put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, SESSION_TIMEOUT_MILLIS)
        put(ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG, HEARTBEAT_MILLIS)
        if (group != null) put(ConsumerConfig.GROUP_ID_CONFIG, group)
        if (Stamps.on) put(ConsumerConfig.INTERCEPTOR_CLASSES_CONFIG, PollStamps::class.java.name)
    }

    private fun produce(topic: String, count: Int) {
        val producer = KafkaProducer(
            mapOf<String, Any>(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
                ProducerConfig.LINGER_MS_CONFIG to 0,
            ),
            StringSerializer(),
            StringSerializer(),
        )
        producer.use {
            val sent = List(count) { i -> it.send(ProducerRecord(topic, "key$i", "$i${alphanumeric(VALUE_BYTES)}")) }
            for (acknowledged in sent) acknowledged.get(SETUP_SECONDS, TimeUnit.SECONDS)
        }
    }

    private fun alphanumeric(length: Int): String {
        val chars = ('a'..'z') + ('A'..'Z') + ('0'..'9')
        return String(CharArray(length) { chars[Random.nextInt(chars.size)] })
    }
}

/** zio-kafka's embedded broker: random ports, no initial rebalance delay, short sessions allowed. */
@Suppress("UNCHECKED_CAST")
fun startBroker(): EmbeddedK {
    val (kafkaPort, controllerPort) =
        ServerSocket(0).use { first -> ServerSocket(0).use { second -> first.localPort to second.localPort } }
    val properties = EmbeddedKafkaConfig.`apply$default$3`()
        .updated("group.initial.rebalance.delay.ms", "0")
        .updated("group.min.session.timeout.ms", "500") as scala.collection.immutable.Map<String, String>
    val config = EmbeddedKafkaConfig.apply(
        kafkaPort,
        controllerPort,
        properties,
        EmbeddedKafkaConfig.`apply$default$4`(),
        EmbeddedKafkaConfig.`apply$default$5`(),
    )
    return EmbeddedKafka.start(config)
}
