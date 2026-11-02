package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.run
import dev.surefold.stream.start
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A record that failed to decode is routed or ends the run, and its offset moves on only once that is done. */
object RoutingSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-routing-test")

    private const val GENEROUS_SECONDS = 60L

    private object Strict : Deserializer<String> {
        override fun deserialize(topic: String, data: ByteArray): String =
            String(data).also { if (it.startsWith("bad")) throw SerializationException("unreadable") }
    }

    private fun decoded(kit: KafkaKit, group: String, topic: String) =
        Kafka.subscribe(
            ConsumerSettings.create(kit.system, ByteArrayDeserializer(), ByteArrayDeserializer())
                .withBootstrapServers(kit.broker.bootstrap)
                .withGroupId(group)
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Topic(topic),
            key = Decoder.string(),
            value = Decoder(Strict) { false },
        )

    private fun committer(kit: KafkaKit): CommitterSettings = CommitterSettings.create(kit.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        test("a diverted record is committed only after its function returns") { kit ->
            kit.broker.send(Topic("diverting"), "fine", "bad", "fine again")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val dead = ConcurrentLinkedQueue<Long>()

            val running = decoded(kit, "diverters", "diverting")
                .divertLefts { error ->
                    entered.countDown()
                    release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    dead.add(error.offset)
                }
                .take(2)
                .runCommitting(committer(kit))
                .start(kit.system)
            entered.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            withClue("while the dead letter is being written, nothing past it is committed") {
                (kit.broker.committed("diverters", Topic("diverting")) ?: 0L) shouldBeLessThanOrEqual 1L
            }
            release.countDown()

            running.exit.settled() shouldBe Exit.Done(Done.getInstance())
            dead.toList() shouldBe listOf(1L)
            kit.broker.committed("diverters", Topic("diverting")) shouldBe 3L
        }

        test("a divert that throws leaves its record uncommitted") { kit ->
            kit.broker.send(Topic("undeliverable"), "fine", "bad")

            decoded(kit, "stranded", "undeliverable")
                .divertLefts { throw IllegalStateException("dead letters are down") }
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()
                .shouldBeInstanceOf<Exit.Died>()

            kit.broker.committed("stranded", Topic("undeliverable")) shouldBe 1L
        }

        test("absolve ends the run with the first record that failed to decode") { kit ->
            kit.broker.send(Topic("strict"), "fine", "bad")

            val exit = decoded(kit, "absolving", "strict")
                .absolve()
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit.shouldBeInstanceOf<Exit.Failed<DecodeError>> { failed -> failed.error.offset shouldBe 1L }
            kit.broker.committed("absolving", Topic("strict")) shouldBe 1L
        }
    }
}
