package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.LongSerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import org.apache.kafka.common.utils.Exit

/** The test broker's own helpers, which every transactional claim in this module is read through. */
object KafkaBrokerSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val COMMITS_AFTER_MILLIS = 500L

    init {
        test("readAll waits for a transaction still ending, and reads what it committed") { kafka ->
            KafkaProducer(
                mapOf<String, Any>(
                    ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                    ProducerConfig.TRANSACTIONAL_ID_CONFIG to "still-ending",
                ),
                StringSerializer(),
                StringSerializer(),
            ).use { producer ->
                producer.initTransactions()
                producer.beginTransaction()
                producer.send(ProducerRecord("still-ending", "committed")).get()
                // Stands in for a commit marker the broker has yet to write, which a read straight after
                // commitTransaction can find missing: until it lands, read_committed stops before the transaction.
                val committing = Thread.ofVirtual().start {
                    Thread.sleep(COMMITS_AFTER_MILLIS)
                    producer.commitTransaction()
                }

                val read = kafka.readAll(Topic("still-ending"), "read_committed").map { it.value() }
                committing.join()
                read shouldBe listOf("committed")
            }
        }

        test("records reads every partition to its end, by the deserializers given") { kafka ->
            val topic = Topic("typed")
            kafka.create(topic, partitions = 2)
            (1L..4L).forEach { n -> kafka.send(topic, n, "event $n", LongSerializer(), StringSerializer()) }

            val read = kafka.records(topic, LongDeserializer(), StringDeserializer())

            read.map { it.key() to it.value() }.sortedBy { it.first } shouldBe (1L..4L).map { it to "event $it" }
            read.map { it.partition() }.toSet() shouldBe setOf(0, 1)
        }

        test("send writes a key and a value by the serializers given") { kafka ->
            val topic = Topic("keyed")
            kafka.send(topic, "junk", "not avro".toByteArray(), StringSerializer(), ByteArraySerializer())

            val read = kafka.records(topic, StringDeserializer(), ByteArrayDeserializer()).single()
            read.key() shouldBe "junk"
            read.value().decodeToString() shouldBe "not avro"
        }

        test("a broker that asks the JVM to exit fails the test that is running instead") { _ ->
            // What a fatal fault in the broker does, through ProcessTerminatingFaultHandler.
            shouldThrow<IllegalStateException>({ Exit.exit(1) }) { thrown ->
                thrown.message shouldContain "asked the JVM to exit with 1"
            }
            shouldThrow<IllegalStateException>({ Exit.halt(1) }) { thrown ->
                thrown.message shouldContain "asked the JVM to halt with 1"
            }
        }
    }
}
