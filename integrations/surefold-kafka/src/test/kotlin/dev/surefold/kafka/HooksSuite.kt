package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.stream.Exit
import dev.surefold.stream.run
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** Spec 0159: what a consumer's caller hears of the partitions each rebalance gives and takes. */
object HooksSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-hooks-test")

    private const val GENEROUS_SECONDS = 60L

    private val strings = StringDeserializer()

    private fun properties(kit: KafkaKit, group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    private class Refused : RuntimeException("this service will not take partitions today")

    init {
        backends.forEach { on ->
            test("a run hears of the partitions it is given, and of those taken back as it closes [$on]") { kit ->
                val topic = Topic("hooked-$on")
                kit.broker.create(topic, 2)
                kit.broker.sendTo(topic, 0, "a")
                val both = setOf(TopicPartition(topic.name, 0), TopicPartition(topic.name, 1))
                val heard = ConcurrentLinkedQueue<Pair<String, Set<TopicPartition>>>()
                val committedOnRevoke = ConcurrentLinkedQueue<Long?>()

                Kafka.consume(
                    properties(kit, "hooked-$on"),
                    topic,
                    key = strings,
                    value = strings,
                    onAssigned = { heard += "assigned" to it },
                    onRevoked = {
                        heard += "revoked" to it
                        committedOnRevoke += kit.broker.committed("hooked-$on", topic, 0)
                    },
                )
                    .take(1)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled() shouldBe Exit.Done(1L)

                heard.toList() shouldBe listOf("assigned" to both, "revoked" to both)
                // The revoke had committed what was handled before the hook heard of it.
                committedOnRevoke.toList() shouldBe listOf(1L)
            }

            test("a hook that throws ends the run with what it threw [$on]") { kit ->
                val topic = Topic("refused-$on")
                kit.broker.create(topic, 1)
                kit.broker.send(topic, "a")

                val exit = Kafka.consume(
                    properties(kit, "refused-$on"),
                    topic,
                    key = strings,
                    value = strings,
                    onAssigned = { throw Refused() },
                )
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit.shouldBeInstanceOf<Exit.Died> { died ->
                    generateSequence(died.cause) { it.cause }.any { it is Refused } shouldBe true
                }
            }
        }
    }
}
