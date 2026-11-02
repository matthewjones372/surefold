package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.capturingMetrics
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.withClue
import dev.surefold.stream.Exit
import dev.surefold.stream.Stream
import dev.surefold.stream.of
import dev.surefold.stream.run
import dev.surefold.stream.runCollect
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.errors.RecordTooLargeException
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

/** One description of a producer, run on whichever backend the run names, against one broker. */
object ProduceSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-produce-test")

    private const val GENEROUS_SECONDS = 60L

    /** Small enough that one record in a test is refused outright, which the producer does not retry. */
    private const val MAX_REQUEST_BYTES = 1024

    private fun consumerProperties(kafka: KafkaBroker, group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private fun producerProperties(kafka: KafkaBroker): Map<String, Any> = mapOf(
        ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
        ProducerConfig.MAX_REQUEST_SIZE_CONFIG to MAX_REQUEST_BYTES,
    )

    private fun consumed(kafka: KafkaBroker, group: String, topic: Topic) = Kafka.consume(
        consumerProperties(kafka, group),
        topic,
        key = StringDeserializer(),
        value = StringDeserializer(),
    )

    private fun strings(kafka: KafkaBroker) =
        Kafka.producer(producerProperties(kafka), StringSerializer(), StringSerializer())

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        backends.forEach { on ->
            test("publishTo writes every element in order, and passes each on once the broker has it [$on]") { kit ->
                val kafka = kit.broker
                val topic = Topic("written-$on")

                strings(kafka).use { producer ->
                    Stream.of("a", "b", "c", "d", "e")
                        .publishTo(producer) { letter -> topic.record(letter, letter.uppercase()) }
                        .runCollect()
                        .run(kit.backend(on))
                        .settled() shouldBe Exit.Done(listOf("a", "b", "c", "d", "e"))
                }

                kafka.read(topic, 5).map { it.key() to it.value() } shouldBe
                    listOf("a" to "A", "b" to "B", "c" to "C", "d" to "D", "e" to "E")
            }
        }

        backends.forEach { on ->
            test("a consumed record is committed once what it produced is acknowledged [$on]") { kit ->
                val kafka = kit.broker
                val input = Topic("orders-in-$on")
                val output = Topic("orders-out-$on")
                kafka.send(input, "1", "2", "3")

                strings(kafka).use { producer ->
                    consumed(kafka, "relaying-$on", input)
                        .mapRecord { record -> record.value() }
                        .publishRecord(producer) { order -> output.record(order, "placed $order") }
                        .take(3)
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled() shouldBe Exit.Done(3L)
                }

                kafka.read(output, 3).map { it.value() } shouldBe
                    listOf("placed 1", "placed 2", "placed 3")
                kafka.committed("relaying-$on", input) shouldBe 3L
            }
        }

        backends.forEach { on ->
            test("a record the producer gives up on is a defect, and nothing from it on is committed [$on]") { kit ->
                val kafka = kit.broker
                val input = Topic("oversized-in-$on")
                kafka.send(input, "small", "x".repeat(MAX_REQUEST_BYTES * 2), "small again")

                val exit = strings(kafka).use { producer ->
                    consumed(kafka, "oversized-$on", input)
                        .mapRecord { record -> record.value() }
                        .publishRecord(producer, inFlight = 1) { value ->
                            Topic("oversized-out-$on").record(value, value)
                        }
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled()
                }

                exit.shouldBeInstanceOf<Exit.Died>()
                kafka.committed("oversized-$on", input) shouldBe 1L
            }
        }

        backends.forEach { on ->
            test(
                "a dead letter is written as it was read, with where it came from, before its offset moves on [$on]",
            ) { kit ->
                val kafka = kit.broker
                val input = Topic("letters-in-$on")
                val dead = Topic("letters-dead-$on")
                kafka.send(input, "fine", "bad", "fine again")
                val strict = Deserializer { _, data: ByteArray ->
                    String(data).also { if (it == "bad") throw SerializationException("unreadable") }
                }

                val captured = capturingMetrics { captured ->
                    val raw = ByteArraySerializer()
                    Kafka.producer(producerProperties(kafka), raw, raw).use { bytes ->
                        Kafka.consume(
                            consumerProperties(kafka, "letters-$on"),
                            input,
                            key = Decoder.string(),
                            value = Decoder(strict) { false },
                        )
                            .divertLefts(bytes.deadLetters(dead))
                            .take(2)
                            .runCommitting()
                            .run(kit.backend(on))
                            .settled() shouldBe Exit.Done(2L)
                    }
                    captured
                }
                captured.counter("surefold.kafka.dead.letters") shouldBe 1.0
                captured.tags("surefold.kafka.dead.letters")["topic"] shouldBe input.name

                val letter = kafka.read(dead, 1).single()
                fun header(name: String) =
                    letter.headers().lastHeader("$DEAD_LETTER_HEADER.$name")?.value()?.let(::String)
                letter.value() shouldBe "bad"
                header("topic") shouldBe input.name
                header("offset") shouldBe "1"
                header("part") shouldBe "Value"
                kafka.committed("letters-$on", input) shouldBe 3L
            }
        }

        test("publishAll answers each record in order, and omits what came after one the producer gave up on") { kit ->
            val kafka = kit.broker
            val topic = Topic("published-all")
            val big = "x".repeat(MAX_REQUEST_BYTES * 2)

            val outcomes = strings(kafka).use { producer ->
                producer.publishAll(listOf("a", "b", big, "d").map { value -> topic.record(value, value) })
            }

            outcomes.take(2).map { it.getOrNull() } shouldBe
                listOf(Published(topic.name, 0, 0L), Published(topic.name, 0, 1L))
            val (failed, omitted) = outcomes.drop(2).map { it.leftOrNull()!! }
            failed.cause.shouldBeInstanceOf<RecordTooLargeException>()
            withClue("the record after the failure is not sent, and says which failure stopped it") {
                omitted.record.value() shouldBe "d"
                omitted.cause.shouldBeInstanceOf<PublishOmitted> { it.cause shouldBe failed.cause }
            }
            kafka.read(topic, 2).map { it.value() } shouldBe listOf("a", "b")
        }

        backends.forEach { on ->
            test(
                "a batch whose write fails ends on absolve, with only what came before the failure committed [$on]",
            ) { kit ->
                val kafka = kit.broker
                val input = Topic("batch-failing-in-$on")
                val output = Topic("batch-failing-out-$on")
                kafka.send(input, "1", "2", "x".repeat(MAX_REQUEST_BYTES * 2), "4", "5")

                val exit = strings(kafka).use { producer ->
                    consumed(kafka, "batch-failing-$on", input)
                        .mapRecord { record -> record.value() }
                        .publishBatch(producer, batch = 5, within = 10.seconds) { value -> output.record(value, value) }
                        .absolve()
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled()
                }

                exit.shouldBeInstanceOf<Exit.Failed<PublishFailed>> { failed ->
                    failed.error.cause.shouldBeInstanceOf<RecordTooLargeException>()
                }
                kafka.read(output, 2).map { it.value() } shouldBe listOf("1", "2")
                kafka.committed("batch-failing-$on", input) shouldBe 2L
            }
        }

        backends.forEach { on ->
            test(
                "divertLefts hands on a batch's failed and omitted records, and the next batch commits past them [$on]",
            ) { kit ->
                val kafka = kit.broker
                val input = Topic("batch-diverted-in-$on")
                kafka.send(input, "1", "2", "x".repeat(MAX_REQUEST_BYTES * 2), "4", "5", "6")
                val lefts = ConcurrentLinkedQueue<PublishFailed>()

                strings(kafka).use { producer ->
                    consumed(kafka, "batch-diverted-$on", input)
                        .mapRecord { record -> record.value() }
                        .publishBatch(producer, batch = 5, within = 1.seconds) { value ->
                            Topic("batch-diverted-out-$on").record(value, value)
                        }
                        .divertLefts(lefts::add)
                        .take(3)
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled() shouldBe Exit.Done(3L)
                }

                lefts.map { it.record.value() to it.cause::class } shouldBe listOf(
                    "x".repeat(MAX_REQUEST_BYTES * 2) to RecordTooLargeException::class,
                    "4" to PublishOmitted::class,
                    "5" to PublishOmitted::class,
                )
                withClue(
                    "a Left commits with the next Right on its partition, here the second batch's one record",
                ) {
                    kafka.committed("batch-diverted-$on", input) shouldBe 6L
                }
            }
        }

        test("publish answers where the record went, or why the producer gave up on it") { kit ->
            val kafka = kit.broker
            val topic = Topic("published")

            val captured = capturingMetrics { captured ->
                strings(kafka).use { producer ->
                    producer.publish(topic.record("k", "v")).getOrNull() shouldBe Published(topic.name, 0, 0L)
                    producer.publish(topic.record("k", "x".repeat(MAX_REQUEST_BYTES * 2))).leftOrNull()!!
                        .cause.shouldBeInstanceOf<RecordTooLargeException>()
                }
                captured
            }

            captured.counter("surefold.kafka.producer.records") shouldBe 1.0
            withClue("the acknowledged record's key and value") {
                captured.counter("surefold.kafka.producer.bytes") shouldBe 2.0
            }
            captured.counter("surefold.kafka.producer.failures") shouldBe 1.0
            captured.histogram("surefold.kafka.producer.ack").size shouldBe 1
            captured.tags("surefold.kafka.producer.records")["topic"] shouldBe topic.name
        }
    }
}
