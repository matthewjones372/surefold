package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.Schedule
import dev.surefold.capturingMetrics
import dev.surefold.fail.fold
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.expect
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldBeLessThan
import dev.surefold.junit.shouldContainAll
import dev.surefold.junit.shouldNotBe
import dev.surefold.junit.shouldNotBeEmpty
import dev.surefold.junit.withClue
import dev.surefold.metricTagged
import dev.surefold.stream.Exit
import dev.surefold.stream.restartOnDefect
import dev.surefold.stream.run
import dev.surefold.stream.start
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.errors.SerializationException
import org.apache.kafka.common.serialization.Deserializer
import org.apache.kafka.common.serialization.StringDeserializer
import java.io.IOException
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

/** One description of a consumer, run on whichever backend the run names, against one broker. */
object ConsumeSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-consume-test")

    private const val GENEROUS_SECONDS = 60L
    private const val SLOW_RECORD_MILLIS = 500L
    private const val SHORT_POLL_INTERVAL_MILLIS = 1500
    private const val SLOW_HANDLER_MILLIS = 4000L

    private fun properties(kit: KafkaKit, group: String): Map<String, Any> = mapOf(
        ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kit.broker.bootstrap,
        ConsumerConfig.GROUP_ID_CONFIG to group,
        ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
    )

    private fun strings(kit: KafkaKit, group: String, topic: Topic) =
        Kafka.consume(properties(kit, group), topic, key = StringDeserializer(), value = StringDeserializer())

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        backends.forEach { on ->
            test(
                "the records a run handles are committed by the time it ends, and the group starts after them [$on]",
            ) { kit ->
                val topic = Topic("orders-$on")
                kit.broker.send(topic, "1", "2", "3", "4", "5")

                strings(kit, "placing-$on", topic).take(5).runCommitting().run(kit.backend(on))
                    .settled() shouldBe Exit.Done(5L)

                withClue("the exit completes after the consumer committed and closed") {
                    kit.broker.committed("placing-$on", topic) shouldBe 5L
                }

                kit.broker.send(topic, "6")
                val seen = ConcurrentLinkedQueue<String>()
                strings(kit, "placing-$on", topic)
                    .mapRecord { it.value().also(seen::add) }
                    .take(1)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled() shouldBe Exit.Done(1L)
                seen.toList() shouldBe listOf("6")
            }

            test("a batch over two partitions commits each one's last offset in it [$on]") { kit ->
                val topic = Topic("batched-$on")
                kit.broker.create(topic, partitions = 2)
                kit.broker.sendTo(topic, 0, "a", "b", "c")
                kit.broker.sendTo(topic, 1, "x", "y")

                val sizes = ConcurrentLinkedQueue<Int>()
                val exit = strings(kit, "inserting-$on", topic)
                    .groupedRecord(5, 10.seconds)
                    .mapRecord { records -> sizes.add(records.size) }
                    .take(1)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit shouldBe Exit.Done(1L)
                sizes.toList() shouldBe listOf(5)
                kit.broker.committed("inserting-$on", topic, number = 0) shouldBe 3L
                kit.broker.committed("inserting-$on", topic, number = 1) shouldBe 2L
            }

            test("the next run in the group starts after the last batch committed [$on]") { kit ->
                val topic = Topic("batches-$on")
                kit.broker.send(topic, "1", "2", "3", "4", "5")

                strings(kit, "bulk-$on", topic).groupedRecord(3, 10.seconds).take(1).runCommitting()
                    .run(kit.backend(on))
                    .settled() shouldBe Exit.Done(1L)

                val seen = ConcurrentLinkedQueue<String>()
                strings(kit, "bulk-$on", topic)
                    .groupedRecord(2, 10.seconds)
                    .mapRecord { records -> records.forEach { seen.add(it.value()) } }
                    .take(1)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled() shouldBe Exit.Done(1L)
                seen.toList() shouldBe listOf("4", "5")
            }

            test(
                "mapParRecord passes on what it handled while the topic is quiet, and all of it is committed [$on]",
            ) { kit ->
                val topic = Topic("in-parallel-$on")
                kit.broker.send(topic, "1", "2", "3")

                val exit = strings(kit, "handling-$on", topic)
                    .mapParRecord(4) { record -> record.value().toInt() * 10 }
                    .take(3)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit shouldBe Exit.Done(3L)
                kit.broker.committed("handling-$on", topic) shouldBe 3L
            }

            test(
                "a stop while the consumer waits on a quiet topic wakes it, and what was handled is committed [$on]",
            ) { kit ->
                val topic = Topic("quiet-$on")
                kit.broker.send(topic, "only")
                val handled = CountDownLatch(1)

                val running = strings(kit, "stopping-$on", topic)
                    .mapRecord { it.value().also { handled.countDown() } }
                    .runCommitting()
                    .start(kit.backend(on))
                handled.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true

                running.stop()

                running.exit.settled() shouldBe Exit.Done(1L)
                kit.broker.committed("stopping-$on", topic) shouldBe 1L
            }

            test("a record that fails to decode is a Left, and is still committed [$on]") { kit ->
                val topic = Topic("mixed-$on")
                kit.broker.send(topic, "fine", "bad")
                val strict = Deserializer { _, data: ByteArray ->
                    String(data).also { if (it == "bad") throw SerializationException("unreadable") }
                }
                val seen = ConcurrentLinkedQueue<Any>()

                val exit = Kafka.consume(
                    properties(kit, "decoding-$on"),
                    topic,
                    key = Decoder.string(),
                    value = Decoder(strict) { false },
                )
                    .mapRecord { decoded -> decoded.fold({ it }, { it.value() }).also(seen::add) }
                    .take(2)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit shouldBe Exit.Done(2L)
                seen.first() shouldBe "fine"
                seen.last().shouldBeInstanceOf<DecodeError> { error -> error.offset shouldBe 1L }
                kit.broker.committed("decoding-$on", topic) shouldBe 2L
            }

            test("divertLefts hands a bad record on before anything after it is committed [$on]") { kit ->
                val topic = Topic("diverted-$on")
                kit.broker.send(topic, "fine", "bad", "fine again")
                val strict = Deserializer { _, data: ByteArray ->
                    String(data).also { if (it == "bad") throw SerializationException("unreadable") }
                }
                val dead = ConcurrentLinkedQueue<Long>()

                val exit = Kafka.consume(
                    properties(kit, "diverting-$on"),
                    topic,
                    key = Decoder.string(),
                    value = Decoder(strict) { false },
                )
                    .divertLefts { error -> dead.add(error.offset) }
                    .take(2)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit shouldBe Exit.Done(2L)
                dead.toList() shouldBe listOf(1L)
                kit.broker.committed("diverting-$on", topic) shouldBe 3L
            }

            test("a registry that is down restarts the consumer, which reads the same record again [$on]") { kit ->
                val topic = Topic("flaky-$on")
                kit.broker.send(topic, "order")
                val asked = AtomicInteger()
                val registryOnce = Deserializer { _, data: ByteArray ->
                    if (asked.incrementAndGet() == 1) throw SerializationException("registry", IOException("refused"))
                    String(data)
                }
                val down: (Throwable) -> Boolean = { t -> generateSequence(t) { it.cause }.any { it is IOException } }

                val exit = Kafka.consume(
                    properties(kit, "restarting-$on"),
                    topic,
                    key = Decoder.string(),
                    value = Decoder(registryOnce, down),
                )
                    .restartOnDefect(Schedule.recurs<Throwable>(1))
                    .mapRecord { decoded -> decoded.fold({ "left" }, { it.value() }) }
                    .take(1)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                exit shouldBe Exit.Done(1L)
                asked.get() shouldBe 2
                kit.broker.committed("restarting-$on", topic) shouldBe 1L
            }

            test(
                "mapParPartitioned lets a partition pass another's slow record, and keeps each one's order [$on]",
            ) { kit ->
                val topic = Topic("partitioned-$on")
                kit.broker.create(topic, partitions = 2)
                kit.broker.sendTo(topic, 0, "slow", "after slow")
                kit.broker.sendTo(topic, 1, "a", "b")
                val arrived = ConcurrentLinkedQueue<String>()
                val never = CountDownLatch(1)

                val exit = strings(kit, "partitioned-$on", topic)
                    .mapParPartitioned(4) { record ->
                        if (record.value() == "slow") never.await(SLOW_RECORD_MILLIS, TimeUnit.MILLISECONDS)
                        record.value()
                    }
                    .mapRecord { value -> value.also(arrived::add) }
                    .take(4)
                    .runCommitting()
                    .run(kit.backend(on))
                    .settled()

                val order = arrived.toList()
                exit shouldBe Exit.Done(4L)
                withClue("partition 1 was not held behind partition 0's slow record: $order") {
                    order.indexOf("b") shouldBeLessThan order.indexOf("slow")
                }
                order.filter { it in setOf("slow", "after slow") } shouldBe listOf("slow", "after slow")
                order.filter { it in setOf("a", "b") } shouldBe listOf("a", "b")
                withClue("partition 0's two records") {
                    kit.broker.committed("partitioned-$on", topic) shouldBe 2L
                }
            }

            test(
                "a handler slower than the poll interval keeps its group, and each record is handled once [$on]",
            ) { kit ->
                val topic = Topic("slow-handler-$on")
                kit.broker.send(topic, "1", "2", "3")
                // A group that drops a member which has not polled for 1.5s; the first record takes 4s to handle.
                val slow = properties(kit, "slow-$on") +
                    (ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG to SHORT_POLL_INTERVAL_MILLIS)
                val seen = ConcurrentLinkedQueue<String>()
                val never = CountDownLatch(1)

                // What the meters read as the slow handler started, and as it finished.
                val pollsBefore = AtomicInteger()
                val pollsAfter = AtomicInteger()
                val pausedDuring = AtomicReference<Double?>()
                val (exit, captured) = capturingMetrics { captured ->
                    Kafka.consume(slow, topic, key = StringDeserializer(), value = StringDeserializer())
                        .mapRecord { record ->
                            if (record.value() == "1") {
                                pollsBefore.set(captured.histogram("surefold.kafka.consumer.poll").size)
                                never.await(SLOW_HANDLER_MILLIS, TimeUnit.MILLISECONDS)
                                pollsAfter.set(captured.histogram("surefold.kafka.consumer.poll").size)
                                pausedDuring.set(captured.gauge("surefold.kafka.consumer.paused"))
                            }
                            record.value().also(seen::add)
                        }
                        .take(3)
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled() to captured
                }

                // On Pekko the source reads ahead on a thread of its own, which keeps polling: the keeper has nothing
                // to do.
                if (on == "Forks") {
                    withClue("the keeper paused the partitions it polled for, and its polls were timed") {
                        pausedDuring.get() shouldBe 1.0
                        pollsAfter.get() shouldBeGreaterThan pollsBefore.get()
                    }
                    withClue("nothing is paused once the consumer has closed") {
                        captured.gauge("surefold.kafka.consumer.paused") shouldBe 0.0
                    }
                }
                val twice = "no record was handed to this consumer twice, as it would be once its group had moved on"
                withClue(twice) {
                    seen.toList() shouldBe listOf("1", "2", "3")
                }
                exit shouldBe Exit.Done(3L)
                kit.broker.committed("slow-$on", topic) shouldBe 3L
            }

            test(
                "a run records what it read, how long it polled, committed and closed, and what it owned [$on]",
            ) { kit ->
                val topic = Topic("metered-$on")
                kit.broker.send(topic, "one", "two", "three")

                val inFlight = ConcurrentLinkedQueue<Double>()
                val (exit, captured) = capturingMetrics { captured ->
                    metricTagged("service" to "orders") {
                        strings(kit, "metering-$on", topic)
                            .mapRecord { record ->
                                captured.gauge("surefold.kafka.consumer.in.flight")?.let(inFlight::add)
                                record.value()
                            }
                            .take(3)
                            .runCommitting()
                            .run(kit.backend(on))
                            .settled()
                    } to captured
                }
                withClue("each record was in flight while it was handled: $inFlight") {
                    inFlight.size shouldBe 3
                    expect(inFlight.all { count -> count >= 1.0 })
                }

                val expectedTags = mapOf(
                    "service" to "orders",
                    "group" to "metering-$on",
                    "topic" to topic.name,
                    "partition" to "0",
                )
                val meters =
                    listOf("records", "bytes", "poll", "commit", "close", "rebalances", "assigned", "in.flight")
                exit shouldBe Exit.Done(3L)
                captured.counter("surefold.kafka.consumer.records") shouldBe 3.0
                withClue("each value's bytes, and no key") {
                    captured.counter("surefold.kafka.consumer.bytes") shouldBe 11.0
                }
                captured.histogram("surefold.kafka.consumer.poll").shouldNotBeEmpty()
                withClue("the close commits what was handled, and waits for it") {
                    captured.histogram("surefold.kafka.consumer.commit").shouldNotBeEmpty()
                }
                captured.histogram("surefold.kafka.consumer.close").size shouldBe 1
                captured.counter("surefold.kafka.consumer.rebalances") shouldNotBe 0.0
                withClue("nothing is owned or in flight once the consumer has left") {
                    captured.gauge("surefold.kafka.consumer.assigned") shouldBe 0.0
                    captured.gauge("surefold.kafka.consumer.in.flight") shouldBe 0.0
                }
                captured.tags("surefold.kafka.consumer.records").entries shouldContainAll expectedTags.entries
                meters.forEach { meter ->
                    withClue("$meter reads back") {
                        captured.read("surefold.kafka.consumer.$meter").shouldNotBeEmpty()
                    }
                }
            }

            test("two consumers in one group with no client id each keep their own meters [$on]") { kit ->
                val topic = Topic("shared-$on")
                kit.broker.send(topic, "1", "2")
                fun clientOfOneRun() = capturingMetrics { captured ->
                    val exit = strings(kit, "sharing-$on", topic).take(1).runCommitting().run(kit.backend(on))
                        .settled()
                    exit shouldBe Exit.Done(1L)
                    captured.tags("surefold.kafka.consumer.assigned")["client"]
                }

                val first = clientOfOneRun()
                val second = clientOfOneRun()

                withClue("each consumer is named, so its gauges are its own: $first, $second") {
                    first.isNullOrEmpty() shouldBe false
                    first shouldNotBe second
                }
            }

            test(
                "lag reads what is left unread, then nothing, and latency how stale each handled record was [$on]",
            ) { kit ->
                val topic = Topic("lagging-$on")
                kit.broker.send(topic, "1", "2", "3", "4", "5")
                // One record a poll, so a poll leaves the rest fetched and unread.
                val oneAtATime = properties(kit, "lagging-$on") + (ConsumerConfig.MAX_POLL_RECORDS_CONFIG to 1)
                val lags = ConcurrentLinkedQueue<Double?>()

                val (exit, captured) = capturingMetrics { captured ->
                    Kafka.consume(oneAtATime, topic, key = StringDeserializer(), value = StringDeserializer())
                        .mapRecord { lags.add(captured.gauge("surefold.kafka.consumer.lag")) }
                        .take(5)
                        .runCommitting()
                        .run(kit.backend(on))
                        .settled() to captured
                }

                // Pekko's source reads ahead of the handler, so only the last record's reading is the same on both.
                if (on == "Forks") lags.first() shouldBe 4.0
                val latencies = captured.histogram("surefold.kafka.consumer.latency")
                val lagTags = mapOf("topic" to topic.name, "partition" to "0")
                exit shouldBe Exit.Done(5L)
                lags.last() shouldBe 0.0
                captured.tags("surefold.kafka.consumer.lag").entries shouldContainAll lagTags.entries
                withClue("a partition this consumer has left lags nothing here") {
                    captured.gauge("surefold.kafka.consumer.lag") shouldBe 0.0
                }
                latencies.size shouldBe 5
                expect(latencies.all { seconds -> seconds < GENEROUS_SECONDS.toDouble() })
            }
        }
    }
}
