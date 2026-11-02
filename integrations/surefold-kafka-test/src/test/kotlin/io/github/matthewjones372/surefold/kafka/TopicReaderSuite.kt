package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.LongDeserializer
import org.apache.kafka.common.serialization.LongSerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Spec 0117: a test reads what is on a topic now, without joining a group or waiting for more. */
object TopicReaderSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        test("a topic of three records on two partitions is read whole, at once") { kafka ->
            val topic = Topic("three")
            kafka.create(topic, partitions = 2)
            kafka.sendTo(topic, 0, "a", "b")
            kafka.sendTo(topic, 1, "c")

            Kafka.reading(topic, kafka.bootstrap, StringDeserializer(), StringDeserializer()).use { reader ->
                val read = measureTimedValue { reader.records() }
                read.value.map { it.partition() to it.value() } shouldBe listOf(0 to "a", 0 to "b", 1 to "c")
                withClue("the read took ${read.duration}") { (read.duration < 5.seconds) shouldBe true }
                reader.values() shouldBe listOf("a", "b", "c")
            }
        }

        test("an empty topic reads as no records, at once") { kafka ->
            val topic = Topic("empty")
            kafka.create(topic, partitions = 2)

            Kafka.reading(topic, kafka.bootstrap, StringDeserializer(), StringDeserializer()).use { reader ->
                val read = measureTimedValue { reader.records() }
                read.value shouldBe emptyList()
                withClue("the read took ${read.duration}") { (read.duration < 5.seconds) shouldBe true }
            }
        }

        test("reading a topic leaves every group as it was") { kafka ->
            val topic = Topic("watched")
            kafka.send(topic, "one", "two", "three")
            commitOne(kafka.bootstrap, group = "projection", topic)
            val groups = groups(kafka.bootstrap)
            committedOffset(kafka.bootstrap, "projection", topic) shouldBe 1L

            Kafka.reading(topic, kafka.bootstrap, StringDeserializer(), StringDeserializer()).use { reader ->
                reader.values() shouldBe listOf("one", "two", "three")
                reader.values() shouldBe listOf("one", "two", "three")
            }

            committedOffset(kafka.bootstrap, "projection", topic) shouldBe 1L
            committedOffsets(kafka.bootstrap, "projection", topic) shouldBe mapOf(0 to 1L)
            committedOffset(kafka.bootstrap, "nobody", topic) shouldBe 0L
            groups(kafka.bootstrap) shouldBe groups
        }

        test("a record that cannot be decoded throws, naming where it is") { kafka ->
            val topic = Topic("junk")
            kafka.send(topic, 1L, "fine", LongSerializer(), StringSerializer())
            kafka.send(topic, 2L, "x".toByteArray(), LongSerializer(), ByteArraySerializer())
            kafka.send(topic, "not a long", "junk", StringSerializer(), StringSerializer())

            Kafka.reading(topic, kafka.bootstrap, LongDeserializer(), StringDeserializer()).use { reader ->
                shouldThrow<IllegalStateException>({ reader.records() }) { thrown ->
                    thrown.message shouldContain "the key at junk-0@2"
                }
            }
        }
    }

    /** One record read and committed by [group], which then leaves. */
    private fun commitOne(bootstrap: String, group: String, topic: Topic) {
        val config = mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
            ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1,
        )
        KafkaConsumer(config, StringDeserializer(), StringDeserializer()).use { consumer ->
            consumer.subscribe(listOf(topic.name))
            do {
                val polled = consumer.poll(Duration.ofMillis(100))
            } while (polled.isEmpty)
            consumer.commitSync()
        }
    }

    private fun groups(bootstrap: String): Set<String> =
        Admin.create(mapOf<String, Any>(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap)).use { admin ->
            admin.listGroups().all().get().map { it.groupId() }.toSet()
        }
}
