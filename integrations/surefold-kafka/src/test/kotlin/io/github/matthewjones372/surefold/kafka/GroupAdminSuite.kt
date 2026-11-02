package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.start
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.admin.AdminClientConfig
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Instant
import java.util.concurrent.TimeUnit

/** Spec 0162: the group calls a runbook makes. */
object GroupAdminSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val GENEROUS_SECONDS = 60L

    // Enough that the records written before and after an instant have timestamps either side of it.
    private const val APART_MILLIS = 20L

    private val strings = StringDeserializer()

    private fun admin(kafka: KafkaBroker) =
        Kafka.admin(mapOf(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap))

    private fun consuming(kafka: KafkaBroker, group: String, topic: Topic, questions: ConsumerQuestions? = null) =
        Kafka.consume(
            mapOf(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG to group,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ),
            topic,
            key = strings,
            value = strings,
            questions = questions,
        )

    /** [group] having handled and committed the first [count] records of [topic], and closed. */
    private fun committed(kafka: KafkaBroker, group: String, topic: Topic, count: Long) =
        consuming(kafka, group, topic).take(count).runCommitting().run(Forks())
            .toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(count)

    init {
        test("a group that committed two of five is listed, and three behind") { kafka ->
            val topic = Topic("lagging")
            kafka.create(topic, 1)
            kafka.send(topic, "1", "2", "3", "4", "5")
            committed(kafka, "lagging", topic, 2)

            admin(kafka).use { admin ->
                ("lagging" in admin.groups()) shouldBe true
                admin.lag("lagging") shouldBe mapOf(TopicPartition(topic.name, 0) to 3L)
            }
        }

        test("a group with no members is reset to the start, an offset, a time and the end") { kafka ->
            val topic = Topic("replayed")
            val partition = TopicPartition(topic.name, 0)
            kafka.create(topic, 1)
            kafka.send(topic, "1", "2", "3")
            Thread.sleep(APART_MILLIS)
            val between = Instant.now()
            Thread.sleep(APART_MILLIS)
            kafka.send(topic, "4", "5")
            committed(kafka, "replayed", topic, 5)

            admin(kafka).use { admin ->
                admin.resetOffsets("replayed", topic, From.Earliest) shouldBe Unit.right()
                admin.lag("replayed") shouldBe mapOf(partition to 5L)
                admin.resetOffsets("replayed", topic, From.Offset(4)) shouldBe Unit.right()
                admin.lag("replayed") shouldBe mapOf(partition to 1L)
                admin.resetOffsets("replayed", topic, From.Time(between)) shouldBe Unit.right()
                admin.lag("replayed") shouldBe mapOf(partition to 2L)
                admin.resetOffsets("replayed", topic, From.Latest) shouldBe Unit.right()
                admin.lag("replayed") shouldBe mapOf(partition to 0L)
                shouldThrow<IllegalArgumentException>({ admin.resetOffsets("replayed", topic, From.Committed) }) { }
            }
        }

        test("a group with a member running is GroupActive, and its offsets stay where they were") { kafka ->
            val topic = Topic("running")
            kafka.create(topic, 1)
            kafka.send(topic, "1", "2")
            val questions = ConsumerQuestions()
            val running = consuming(kafka, "running", topic, questions).runCommitting().start(Forks())
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
            fun lag() = try {
                questions.lag()
            } catch (_: IllegalStateException) {
                null
            }
            while (lag() != mapOf(TopicPartition(topic.name, 0) to 0L) && System.nanoTime() < deadline) {
                Thread.sleep(APART_MILLIS)
            }

            admin(kafka).use { admin ->
                admin.resetOffsets("running", topic, From.Earliest) shouldBe GroupActive("running", 1).left()
                admin.lag("running") shouldBe mapOf(TopicPartition(topic.name, 0) to 0L)
            }
            running.stop()
            running.exit.toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(2L)
        }
    }
}
