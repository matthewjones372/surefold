package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.start
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** A committing run stopped drains: the consumer stops fetching, and what is in flight finishes and commits. */
object DrainSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-drain-test")

    private const val GENEROUS_SECONDS = 60L

    init {
        test(
            "a stop while a body is running still commits that body's record, and close waits for the commit",
        ) { kit ->
            kit.broker.send(Topic("slow"), "1", "2")
            val second = CountDownLatch(1)
            val release = CountDownLatch(1)

            val running = Kafka.subscribe(
                ConsumerSettings.create(kit.system, StringDeserializer(), StringDeserializer())
                    .withBootstrapServers(kit.broker.bootstrap)
                    .withGroupId("draining")
                    .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                Topic("slow"),
            )
                .mapParRecord(1) { record ->
                    if (record.value() == "2") {
                        second.countDown()
                        release.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                    }
                    record.value()
                }
                .runCommitting(CommitterSettings.create(kit.system))
                .start(kit.system)
            second.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

            running.stop()
            release.countDown()
            running.close()

            running.exit.toCompletableFuture().getNow(null) shouldBe Exit.Done(Done.getInstance())
            withClue("the kill switch would have dropped record 2 between the stages; a drain commits it") {
                kit.broker.committed("draining", Topic("slow")) shouldBe 2L
            }
        }
    }
}
