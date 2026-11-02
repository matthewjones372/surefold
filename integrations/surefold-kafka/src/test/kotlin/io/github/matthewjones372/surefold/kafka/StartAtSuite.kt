package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Instant
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Spec 0159: where a read or a group's consumer starts, by time or by offsets the caller keeps. */
object StartAtSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-start-at-test")

    private const val GENEROUS_SECONDS = 60L

    // Enough that the records written before and after an instant have timestamps either side of it.
    private const val APART_MILLIS = 20L

    private val strings = StringDeserializer()

    private fun properties(kit: KafkaKit, group: String? = null): Map<String, Any> =
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ) + listOfNotNull(group?.let { ConsumerConfig.GROUP_ID_CONFIG to it })

    /** [topic] with "old-1" and "old-2" written before the instant this answers, and "new-1", "new-2" after it. */
    private fun writtenAround(kit: KafkaKit, topic: Topic): Instant {
        kit.broker.create(topic, 1)
        kit.broker.send(topic, "old-1", "old-2")
        Thread.sleep(APART_MILLIS)
        val between = Instant.now()
        Thread.sleep(APART_MILLIS)
        kit.broker.send(topic, "new-1", "new-2")
        return between
    }

    /** The values the first [count] records [group] consumes from [topic] carry, starting where [startAt] says. */
    private fun consumed(kit: KafkaKit, on: String, group: String, topic: Topic, startAt: StartAt, count: Long) =
        ConcurrentLinkedQueue<String>().also { seen ->
            Kafka.consume(properties(kit, group), topic, key = strings, value = strings, startAt = startAt)
                .mapRecord { it.value().also(seen::add) }
                .take(count)
                .runCommitting()
                .run(kit.backend(on))
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(count)
        }.toList()

    init {
        test("a read from a time starts at the first record written at or after it") { kit ->
            val topic = Topic("read-from-time")
            val between = writtenAround(kit, topic)
            val seen = ConcurrentLinkedQueue<String>()

            Kafka.read(properties(kit), topic, 0, strings, strings, From.Time(between), Until.EndAtStart)
                .mapRecord { it.value().also(seen::add) }
                .runCommitting()
                .run(kit.backend("Forks"))
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(2L)
            seen.toList() shouldBe listOf("new-1", "new-2")
        }

        test("a read from a time after every record starts at the end, and reads nothing") { kit ->
            val topic = Topic("read-from-later")
            writtenAround(kit, topic)
            val later = From.Time(Instant.now().plusSeconds(60))
            Kafka.read(properties(kit), topic, 0, strings, strings, later, Until.EndAtStart)
                .runCommitting()
                .run(kit.backend("Forks"))
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(0L)
        }

        backends.forEach { on ->
            test("a group with nothing committed starts at a time, and one with a commit after it [$on]") { kit ->
                val topic = Topic("consume-from-time-$on")
                val between = writtenAround(kit, topic)

                consumed(kit, on, "fresh-$on", topic, StartAt.Time(between), 2) shouldBe listOf("new-1", "new-2")

                consumed(kit, on, "started-$on", topic, StartAt.Committed, 1) shouldBe listOf("old-1")
                consumed(kit, on, "started-$on", topic, StartAt.Time(between), 1) shouldBe listOf("old-2")
            }

            test("a group that keeps its own offsets starts at them, whatever it committed [$on]") { kit ->
                val topic = Topic("consume-own-$on")
                writtenAround(kit, topic)
                consumed(kit, on, "own-$on", topic, StartAt.Committed, 1) shouldBe listOf("old-1")

                val asked = ConcurrentLinkedQueue<Set<TopicPartition>>()
                val own = StartAt.Own { partitions -> partitions.associateWith { 3L }.also { asked += partitions } }

                consumed(kit, on, "own-$on", topic, own, 1) shouldBe listOf("new-2")
                asked.toList() shouldBe listOf(setOf(TopicPartition(topic.name, 0)))
            }
        }
    }
}
