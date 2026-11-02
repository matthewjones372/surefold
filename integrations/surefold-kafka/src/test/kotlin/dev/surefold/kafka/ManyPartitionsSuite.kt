package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.run
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Spec 0159: one consumer reading many assigned partitions. */
object ManyPartitionsSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val GENEROUS_SECONDS = 60L

    private fun properties(kafka: KafkaBroker, group: String? = null): Map<String, Any> =
        mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap) +
            listOfNotNull(group?.let { ConsumerConfig.GROUP_ID_CONFIG to it })

    /** What one read of [partitions] gives, by partition, once it has ended having handled each record. */
    private fun read(
        kafka: KafkaBroker,
        topic: Topic,
        partitions: List<Int>,
        from: From,
        group: String? = null,
    ): Map<Int, List<String>> {
        val seen = ConcurrentLinkedQueue<Pair<Int, String>>()
        val strings = StringDeserializer()
        val exit = Kafka.read(properties(kafka, group), topic, partitions, strings, strings, from, Until.EndAtStart)
            .mapRecord { record -> (record.partition() to record.value()).also(seen::add) }
            .runCommitting()
            .run(Forks())
            .toCompletableFuture()
            .get(GENEROUS_SECONDS, TimeUnit.SECONDS)
        exit shouldBe Exit.Done(seen.size.toLong())
        return seen.groupBy({ it.first }, { it.second })
    }

    init {
        test("one read of two partitions gives each one's records in order, none of a third, and ends") { kafka ->
            val topic = Topic("several")
            kafka.create(topic, partitions = 3)
            kafka.sendTo(topic, 0, "a", "b", "c")
            kafka.sendTo(topic, 1, "not read")
            kafka.sendTo(topic, 2, "d", "e")

            read(kafka, topic, listOf(0, 2), From.Earliest) shouldBe
                mapOf(0 to listOf("a", "b", "c"), 2 to listOf("d", "e"))
        }

        test("a partition with nothing in it ends at once, and the others are read to their ends") { kafka ->
            val topic = Topic("one-empty")
            kafka.create(topic, partitions = 2)
            kafka.sendTo(topic, 1, "x", "y")

            read(kafka, topic, listOf(0, 1), From.Earliest) shouldBe mapOf(1 to listOf("x", "y"))
        }

        test("a grouped read commits each partition on its own, and the next read starts after it") { kafka ->
            val topic = Topic("committed-many")
            kafka.create(topic, partitions = 2)
            kafka.sendTo(topic, 0, "a1", "a2")
            kafka.sendTo(topic, 1, "b1")

            read(kafka, topic, listOf(0, 1), From.Earliest, group = "many") shouldBe
                mapOf(0 to listOf("a1", "a2"), 1 to listOf("b1"))
            kafka.committed("many", topic, 0) shouldBe 2L
            kafka.committed("many", topic, 1) shouldBe 1L

            kafka.sendTo(topic, 0, "a3")
            kafka.sendTo(topic, 1, "b2")
            read(kafka, topic, listOf(0, 1), From.Committed, group = "many") shouldBe
                mapOf(0 to listOf("a3"), 1 to listOf("b2"))
        }

        test("a read of no partition is refused") { kafka ->
            val strings = StringDeserializer()
            shouldThrow<IllegalArgumentException>({
                Kafka.read(properties(kafka), Topic("none"), emptyList(), strings, strings, From.Earliest, Until.Never)
            }) { }
        }
    }
}
