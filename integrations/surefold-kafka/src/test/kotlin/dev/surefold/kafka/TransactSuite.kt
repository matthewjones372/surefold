package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.capturingMetrics
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Forks
import dev.surefold.stream.Stream
import dev.surefold.stream.map
import dev.surefold.stream.of
import dev.surefold.stream.restartOnDefect
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Serializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/** A record's outputs and its offset in one transaction, on whichever backend the run names. */
object TransactSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-transact-test")

    private const val GENEROUS_SECONDS = 60L
    private const val COMMITTED = "read_committed"
    private const val EVERYTHING = "read_uncommitted"

    private fun consumed(kafka: KafkaBroker, group: String, topic: Topic) = Kafka.consume(
        mapOf(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
        ),
        topic,
        key = StringDeserializer(),
        value = StringDeserializer(),
    )

    private fun producer(kafka: KafkaBroker, id: String, value: Serializer<String> = StringSerializer()) =
        Kafka.transactionalProducer(
            mapOf(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap),
            transactionalId = id,
            key = StringSerializer(),
            value = value,
        )

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        backends.forEach { on ->
            test("a record's outputs and its offset are committed together [$on]") { kit ->
                val kafka = kit.broker
                val input = Topic("carts-$on")
                val output = Topic("orders-$on")
                kafka.send(input, "1", "2", "3")

                val captured = capturingMetrics { captured ->
                    producer(kafka, "checkout-$on").use { orders ->
                        consumed(kafka, "checkout-$on", input)
                            .mapRecord { record -> record.value() }
                            .take(3)
                            .runTransactionally(orders) { cart -> listOf(output.record(cart, "order $cart")) }
                            .run(kit.backend(on))
                            .settled() shouldBe Exit.Done(3L)
                    }
                    captured
                }

                captured.histogram("surefold.kafka.transactions").shouldNotBeEmpty()
                captured.counter("surefold.kafka.transactions.aborts") shouldBe 0.0
                kafka.readAll(output, COMMITTED).map { it.value() } shouldBe
                    listOf("order 1", "order 2", "order 3")
                withClue("the transaction committed the group's offsets; the consumer committed none of its own") {
                    kafka.committed("checkout-$on", input) shouldBe 3L
                }
            }
        }

        backends.forEach { on ->
            test("a transaction that fails is aborted, and neither its outputs nor its offsets are seen [$on]") { kit ->
                val kafka = kit.broker
                val input = Topic("failing-carts-$on")
                val output = Topic("failing-orders-$on")
                kafka.send(input, "1", "2", "boom")
                val refusing = Serializer<String> { _, value ->
                    if (value == "boom") throw SerializationException("unwritable") else value.toByteArray()
                }

                val (exit, captured) = capturingMetrics { captured ->
                    producer(kafka, "failing-$on", refusing).use { orders ->
                        consumed(kafka, "failing-$on", input)
                            .mapRecord { record -> record.value() }
                            .runTransactionally(orders, batch = 3, within = GENEROUS_SECONDS.seconds) { cart ->
                                listOf(output.record(cart, cart))
                            }
                            .run(kit.backend(on))
                            .settled()
                    } to captured
                }

                exit.shouldBeInstanceOf<Exit.Died>()
                captured.counter("surefold.kafka.transactions.aborts") shouldBe 1.0
                withClue("the two records sent before the failure were aborted with it") {
                    kafka.readAll(output, COMMITTED) shouldBe emptyList()
                }
                kafka.committed("failing-$on", input) shouldBe null
            }
        }

        test("records not from Kafka consume are refused, and the outputs the broker already took are aborted") { kit ->
            val kafka = kit.broker
            val output = Topic("unowned-orders")

            // A handle with no group: the transaction's outputs are sent and acknowledged, and only then refused.
            @OptIn(KafkaSpi::class)
            val unowned = object : Handle {
                override fun handled() = Unit
            }

            @OptIn(KafkaSpi::class)
            val records = Stream.of("1", "2").map { value -> Committed(value, Position("elsewhere", 0, 0L), unowned) }

            val exit = producer(kafka, "unowned").use { orders ->
                records.runTransactionally(orders) { cart -> listOf(output.record(cart, cart)) }.run(Forks()).settled()
            }

            exit.shouldBeInstanceOf<Exit.Died> { died ->
                (listOf(died.cause) + died.cause.suppressed).joinToString { it.message.orEmpty() } shouldContain
                    "only records from Kafka.consume"
            }
            withClue("both records reached the broker, in a transaction that was aborted") {
                kafka.readAll(output, EVERYTHING).map { it.value() } shouldBe listOf("1", "2")
                kafka.readAll(output, COMMITTED) shouldBe emptyList()
            }
        }

        backends.forEach { on ->
            test("a restart after an aborted transaction writes each record exactly once [$on]") { kit ->
                val kafka = kit.broker
                val input = Topic("retried-carts-$on")
                val output = Topic("retried-orders-$on")
                kafka.send(input, "1", "2", "3")
                val attempts = AtomicInteger()
                val onceRefusing = Serializer<String> { _, value ->
                    if (value == "3" && attempts.incrementAndGet() == 1) throw SerializationException("not yet")
                    value.toByteArray()
                }

                producer(kafka, "retried-$on", onceRefusing).use { orders ->
                    consumed(kafka, "retried-$on", input)
                        .mapRecord { record -> record.value() }
                        // No take upstream: the first attempt's consumer is still polling when the restart gives it up.
                        .transacted(orders, batch = 3, within = GENEROUS_SECONDS.seconds) { cart ->
                            listOf(output.record(cart, cart))
                        }
                        .restartOnDefect(Schedule.recurs<Throwable>(1))
                        // Ends after the commit, so the consumer is still in its group when the offsets reach the
                        // broker.
                        .take(1)
                        .runFold(0L, Long::plus)
                        .run(kit.backend(on))
                        .settled() shouldBe Exit.Done(3L)
                }

                kafka.readAll(output, COMMITTED).map { it.value() } shouldBe listOf("1", "2", "3")
                kafka.committed("retried-$on", input) shouldBe 3L
            }
        }
    }
}
