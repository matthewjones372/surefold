package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.stream.Exit
import dev.surefold.stream.start
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Spec 0159: a consumer subscribed to every topic a pattern matches. */
object PatternSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-pattern-test")

    private const val GENEROUS_SECONDS = 60L

    // Short, so a topic created mid-run is found within a second rather than five minutes.
    private const val METADATA_MAX_AGE_MILLIS = 500

    init {
        backends.forEach { on ->
            test("a pattern reads every topic it matches, one created mid-run too, and no other [$on]") { kit ->
                val first = Topic("orders-$on.eu")
                val later = Topic("orders-$on.us")
                kit.broker.create(first, 1)
                kit.broker.create(Topic("refunds-$on.eu"), 1)
                kit.broker.send(first, "eu-1")
                kit.broker.send(Topic("refunds-$on.eu"), "not an order")

                val seen = ConcurrentLinkedQueue<String>()
                val heard = CountDownLatch(1)
                val running = Kafka.consume(
                    mapOf(
                        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
                        ConsumerConfig.GROUP_ID_CONFIG to "pattern-$on",
                        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                        ConsumerConfig.METADATA_MAX_AGE_CONFIG to METADATA_MAX_AGE_MILLIS,
                    ),
                    Topics.matching(Regex("orders-$on\\..*")),
                    key = StringDeserializer(),
                    value = StringDeserializer(),
                )
                    .mapRecord { record -> record.value().also(seen::add).also { heard.countDown() } }
                    .take(2)
                    .runCommitting()
                    .start(kit.backend(on))
                heard.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

                kit.broker.create(later, 1)
                kit.broker.send(later, "us-1")

                running.exit.toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe Exit.Done(2L)
                seen.toList() shouldBe listOf("eu-1", "us-1")
            }
        }
    }
}
