package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.map
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

object ReadSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val GENEROUS_SECONDS = 60L

    // A poll interval the slow handler below outlasts more than twice over.
    private const val SHORT_POLL_INTERVAL_MILLIS = 1_500
    private const val SLOW_HANDLER_MILLIS = 4_000L

    private val backend = Forks()

    private fun properties(kafka: KafkaBroker, group: String? = null): Map<String, Any> =
        mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap) +
            listOfNotNull(group?.let { ConsumerConfig.GROUP_ID_CONFIG to it }) +
            (ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest")

    /** The values a read gave; it is checked to have ended having handled each of them. */
    private class Seen(val values: List<String?>) {

        /** The read gave [expected]. */
        infix fun gives(expected: List<String?>) = values shouldBe expected
    }

    /** The values [partition] of [topic] gives from [from] until [until], each seen by [each] as it is handled. */
    private fun values(
        kafka: KafkaBroker,
        topic: String,
        partition: Int,
        from: From,
        until: Until,
        group: String? = null,
        take: Long = Long.MAX_VALUE,
        each: (String?) -> Unit = {},
    ): Seen {
        val seen = ConcurrentLinkedQueue<String?>()
        val strings = StringDeserializer()
        val exit = Kafka.read(properties(kafka, group), Topic(topic), partition, strings, strings, from, until)
            .mapRecord { record -> record.value().also { seen += it; each(it) } }
            .take(take)
            .runCommitting()
            .run(backend)
            .toCompletableFuture()
            .get(GENEROUS_SECONDS, TimeUnit.SECONDS)
        exit shouldBe Exit.Done(seen.size.toLong())
        return Seen(seen.toList())
    }

    init {
        test("each partition read to its end at start gives its own records and ends") { kafka ->
            kafka.create(Topic("replay"), partitions = 3)
            kafka.sendTo(Topic("replay"), 0, "a", "b")
            kafka.sendTo(Topic("replay"), 1, "c")
            kafka.sendTo(Topic("replay"), 2, "d", "e", "f")

            val reads = (0..2).map { values(kafka, "replay", it, From.Earliest, Until.EndAtStart) }

            reads.map { it.values } shouldBe listOf(listOf("a", "b"), listOf("c"), listOf("d", "e", "f"))
        }

        test("a record written after the read opened is not read when it ends at start") { kafka ->
            kafka.create(Topic("late"), partitions = 1)
            kafka.sendTo(Topic("late"), 0, "a", "b")

            val written = values(kafka, "late", 0, From.Earliest, Until.EndAtStart) {
                if (it == "a") kafka.sendTo(Topic("late"), 0, "late")
            }

            written gives listOf("a", "b")
            values(kafka, "late", 0, From.Earliest, Until.EndAtStart) gives listOf("a", "b", "late")
        }

        test("a partition already at its end gives nothing, at once") { kafka ->
            kafka.create(Topic("empty"), partitions = 2)
            kafka.sendTo(Topic("empty"), 0, "a")

            values(kafka, "empty", 1, From.Earliest, Until.EndAtStart) gives emptyList()
            values(kafka, "empty", 0, From.Latest, Until.EndAtStart) gives emptyList()
        }

        test("a read from an offset starts there") { kafka ->
            kafka.create(Topic("offset"), partitions = 1)
            kafka.sendTo(Topic("offset"), 0, "a", "b", "c")

            values(kafka, "offset", 0, From.Offset(1), Until.EndAtStart) gives listOf("b", "c")
        }

        test("a read stopped and resumed from what it committed loses nothing") { kafka ->
            kafka.create(Topic("resume"), partitions = 1)
            kafka.sendTo(Topic("resume"), 0, "1", "2", "3", "4")

            val stopped = values(kafka, "resume", 0, From.Committed, Until.Never, group = "resumer", take = 2)
            stopped gives listOf("1", "2")
            kafka.committed("resumer", Topic("resume")) shouldBe 2L
            kafka.sendTo(Topic("resume"), 0, "5")
            val resumed = values(kafka, "resume", 0, From.Committed, Until.EndAtStart, group = "resumer")

            resumed gives listOf("3", "4", "5")
        }

        test("a grouped read whose handler outlasts the poll interval keeps its place and commits") { kafka ->
            // An assigned consumer is no member of the group, so no poll interval drops it (spec 0157): a handler
            // slower than max.poll.interval.ms delays the next poll and nothing else.
            val topic = Topic("slow-read")
            kafka.create(topic, partitions = 1)
            kafka.sendTo(topic, 0, "1", "2", "3")
            val slow = properties(kafka, "slow-reader") +
                (ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to SHORT_POLL_INTERVAL_MILLIS)
            val seen = ConcurrentLinkedQueue<String?>()
            val strings = StringDeserializer()

            val exit = Kafka.read(slow, topic, 0, strings, strings, From.Committed, Until.EndAtStart)
                .mapRecord { record ->
                    if (record.value() == "1") Thread.sleep(SLOW_HANDLER_MILLIS)
                    record.value().also(seen::add)
                }
                .runCommitting()
                .run(backend)
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(3L)
            seen.toList() shouldBe listOf("1", "2", "3")
            kafka.committed("slow-reader", topic) shouldBe 3L
        }

        test("a read from the committed offset with no group is refused where it is built") { kafka ->
            val strings = StringDeserializer()

            shouldThrow<IllegalArgumentException>({
                Kafka.read(properties(kafka), Topic("t"), 0, strings, strings, From.Committed, Until.Never)
            }) { refused ->
                refused.message shouldBe
                    "Kafka.read from the committed offset needs a group.id in its properties, and t was read with none"
            }
        }

        test("with decoders, a record that cannot be read is a Left that keeps its place") { kafka ->
            kafka.create(Topic("decoded"), partitions = 1)
            kafka.sendTo(Topic("decoded"), 0, "1", "two", "3")
            val strings = Decoder(StringDeserializer()) { false }
            val ints = Decoder(Deserializer { _, bytes -> String(bytes).toInt() }) { false }
            val seen = ConcurrentLinkedQueue<Either<DecodeError, Int?>>()

            val exit = Kafka
                .read(properties(kafka), Topic("decoded"), 0, strings, ints, From.Earliest, Until.EndAtStart)
                .mapRecord { decoded -> decoded.map { it.value() }.also { seen += it } }
                .runCommitting()
                .run(backend)
                .toCompletableFuture()
                .get(GENEROUS_SECONDS, TimeUnit.SECONDS)

            exit shouldBe Exit.Done(3L)
            seen.map { either -> either.fold({ "error at ${it.offset}" }, { "$it" }) } shouldBe
                listOf("1", "error at 1", "3")
        }
    }
}
