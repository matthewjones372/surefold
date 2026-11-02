package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.serialization.StringSerializer

/** Spec 0160: what a running producer can tell its caller. */
object ProducerQuestionsSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    // Far longer than the test: a send waits on a flush, never on the linger.
    private const val LINGER_MILLIS = 600_000

    private fun producer(kafka: KafkaBroker, linger: Int = 0) = Kafka.producer(
        mapOf(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ProducerConfig.LINGER_MS_CONFIG to linger,
        ),
        StringSerializer(),
        StringSerializer(),
    )

    init {
        test("a flush sends what is lingering at once, and returns once the broker has it") { kafka ->
            val topic = Topic("flushed")
            kafka.create(topic, 1)
            producer(kafka, linger = LINGER_MILLIS).use { producer ->
                val sent = producer.send(topic.record("k", "lingering"))
                sent.isDone shouldBe false

                producer.flush()

                sent.isDone shouldBe true
                sent.get().offset shouldBe 0L
            }
            kafka.read(topic, 1).map { it.value() } shouldBe listOf("lingering")
        }

        test("a producer names the partitions of a topic, in order") { kafka ->
            val topic = Topic("partitioned-for")
            kafka.create(topic, 3)
            producer(kafka).use { producer ->
                producer.partitionsFor(topic).map { it.partition() } shouldBe listOf(0, 1, 2)
            }
        }

        test("a producer's metrics are the client's own, counting what it sent") { kafka ->
            val topic = Topic("metered-producer")
            kafka.create(topic, 1)
            producer(kafka).use { producer ->
                producer.publish(topic.record("k", "v"))
                val sent = producer.metrics().entries
                    .single { (name, _) -> name.name() == "record-send-total" && name.group() == "producer-metrics" }
                (sent.value.metricValue() as Double) shouldBeGreaterThan 0.0
            }
        }
    }
}
