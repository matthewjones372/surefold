package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldThrow
import dev.surefold.stream.Exit
import dev.surefold.stream.start
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 0160: what a running consumer can be asked. */
object ConsumerQuestionsSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-questions-test")

    private const val GENEROUS_SECONDS = 60L

    private val strings = StringDeserializer()

    /**
     * [answer], asked again until it gives [expected] or a generous while has passed, and its last answer: null while
     * the run has yet to open, which a started run does on a thread of its own.
     */
    private fun <A> askedUntil(expected: A, answer: () -> A): A? {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        fun ask() = try {
            answer()
        } catch (_: IllegalStateException) {
            null
        }
        var last = ask()
        while (last != expected && System.nanoTime() < deadline) {
            Thread.sleep(20)
            last = ask()
        }
        return last
    }

    init {
        backends.forEach { on ->
            test("a running consumer answers what it holds, how far behind it is, and its metrics [$on]") { kit ->
                val topic = Topic("questioned-$on")
                val partition = TopicPartition(topic.name, 0)
                kit.broker.create(topic, 1)
                kit.broker.send(topic, "1", "2", "3")

                val questions = ConsumerQuestions()
                val release = CountDownLatch(1)
                val running = Kafka.consume(
                    mapOf(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
                        ConsumerConfig.GROUP_ID_CONFIG to "questioned-$on",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ),
                    topic,
                    key = strings,
                    value = strings,
                    questions = questions,
                )
                    .mapRecord { record ->
                        if (record.value() == "late") release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                        record.value()
                    }
                    .runCommitting()
                    .start(kit.backend(on))

                // Every record handled, and committed before a poll of the quiet topic: nothing unread.
                askedUntil(mapOf(partition to 0L)) { questions.lag() } shouldBe mapOf(partition to 0L)
                questions.assignment() shouldBe setOf(partition)
                val fetched = questions.metrics().entries.single { (name, _) ->
                    name.name() == "records-consumed-total" &&
                        name.group() == "consumer-fetch-manager-metrics" &&
                        "topic" !in name.tags()
                }
                (fetched.value.metricValue() as Double) shouldBeGreaterThan 0.0

                // Two more in hand and not yet handled: two unread.
                kit.broker.send(topic, "late", "later")
                askedUntil(mapOf(partition to 2L)) { questions.lag() } shouldBe mapOf(partition to 2L)

                release.countDown()
                askedUntil(mapOf(partition to 0L)) { questions.lag() } shouldBe mapOf(partition to 0L)

                running.stop()
                running.exit.toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(5L)
                shouldThrow<IllegalStateException>({ questions.lag() }) { }
            }
        }
    }
}
