package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import io.github.matthewjones372.surefold.stream.Exit
import io.github.matthewjones372.surefold.stream.map
import io.github.matthewjones372.surefold.stream.mapOrFail
import io.github.matthewjones372.surefold.stream.run
import io.github.matthewjones372.surefold.stream.runCollect
import io.github.matthewjones372.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** A subscription ends on `runCommitting`, and what it commits is where the group starts next time. */
object SubscribeSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-subscribe-test")

    private const val GENEROUS_SECONDS = 60L

    private data class Refused(val value: String)

    private fun consumer(kit: KafkaKit, group: String): ConsumerSettings<String, String> =
        ConsumerSettings.create(kit.system, StringDeserializer(), StringDeserializer())
            .withBootstrapServers(kit.broker.bootstrap)
            .withGroupId(group)
            .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
            // `take` cancels the consumer rather than draining it, and the connector keeps a cancelled one in
            // its group this long so its commits land; the next run in the group waits for it. 30s by default.
            .withStopTimeout(Duration.ofSeconds(1))

    private fun committer(kit: KafkaKit): CommitterSettings = CommitterSettings.create(kit.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        test("the records a run handles are committed, and the group starts after them next time") { kit ->
            kit.broker.send(Topic("orders"), "1", "2", "3", "4", "5")

            Kafka.subscribe(consumer(kit, "placing"), Topic("orders"))
                .take(5)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled() shouldBe Exit.Done(Done.getInstance())

            kit.broker.committed("placing", Topic("orders")) shouldBe 5L

            kit.broker.send(Topic("orders"), "6")
            val next = Kafka.subscribe(consumer(kit, "placing"), Topic("orders"))
                .map { it.value.value() }
                .take(1)
                .runCollect()
                .run(kit.system)
                .settled()

            withClue("a second run in the same group sees none of the five again") {
                next shouldBe Exit.Done(listOf("6"))
            }
        }

        test("a batch commits through the connector, and the group starts after it next time") { kit ->
            kit.broker.send(Topic("bulk-orders"), "1", "2", "3", "4")

            val exit = Kafka.subscribe(consumer(kit, "bulk"), Topic("bulk-orders"))
                .groupedRecord(3, 10.seconds)
                .take(1)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit shouldBe Exit.Done(Done.getInstance())
            kit.broker.committed("bulk", Topic("bulk-orders")) shouldBe 3L
        }

        test("a declared failure in the stream is the committing run's exit") { kit ->
            kit.broker.send(Topic("refusals"), "fine", "refused")

            Kafka.subscribe(consumer(kit, "refusing"), Topic("refusals"))
                .mapOrFail { record -> if (record.value.value() == "refused") fail(Refused("refused")) else record }
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled() shouldBe Exit.Failed(Refused("refused"))
        }
    }
}
