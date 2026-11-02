package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.capturingLogs
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.logInfo
import dev.surefold.stream.Exit
import dev.surefold.stream.run
import dev.surefold.stream.take
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerSettings
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit

/** The element operators work on the record's value and carry its offset through to `runCommitting`. */
object CommittedOperatorsSuite : SharedSuite<KafkaKit>() {
    override val shared: Resource<KafkaKit> = kafkaKit("surefold-kafka-committed-operators-test")

    private const val GENEROUS_SECONDS = 60L

    private data class Refused(val value: String)

    private fun subscribe(kit: KafkaKit, group: String, topic: String) =
        Kafka.subscribe(
            ConsumerSettings.create(kit.system, StringDeserializer(), StringDeserializer())
                .withBootstrapServers(kit.broker.bootstrap)
                .withGroupId(group)
                .withProperty(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
            Topic(topic),
        )

    private fun committer(kit: KafkaKit): CommitterSettings = CommitterSettings.create(kit.system)

    private fun <E, R> CompletionStage<Exit<E, R>>.settled(): Exit<E, R> =
        toCompletableFuture().get(GENEROUS_SECONDS, TimeUnit.SECONDS)

    init {
        test(
            "mapRecord, filterRecord, mapConcatRecord and mapParRecord see the values, and every record is committed",
        ) { kit ->
            kit.broker.send(Topic("numbers"), "1", "2", "3", "4", "5")
            val seen = ConcurrentLinkedQueue<Int>()

            val exit = subscribe(kit, "counting", "numbers")
                .mapRecord { record -> record.value().toInt() }
                .filterRecord { it % 2 == 1 }
                .mapConcatRecord { listOf(it, it * 10) }
                .mapParRecord(2) { n -> n }
                // Recorded after mapParRecord, not in its body: two bodies run at once, and only the output is ordered.
                .mapRecord { n -> n.also(seen::add) }
                .take(6)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit shouldBe Exit.Done(Done.getInstance())
            seen.toList() shouldBe listOf(1, 10, 3, 30, 5, 50)
            kit.broker.committed("counting", Topic("numbers")) shouldBe 5L
        }

        test("a body that raises on record 3 of 5 leaves the first two committed") { kit ->
            kit.broker.send(Topic("refusals"), "1", "2", "3", "4", "5")

            val exit = subscribe(kit, "refusing", "refusals")
                .mapRecordOrFail { record ->
                    if (record.value() == "3") fail(Refused("3")) else record.value()
                }
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit shouldBe Exit.Failed(Refused("3"))
            withClue("the next run in the group starts at the record that raised") {
                kit.broker.committed("refusing", Topic("refusals")) shouldBe 2L
            }
        }

        test("a record expanded to three elements whose third raises is not committed") { kit ->
            kit.broker.send(Topic("expansions"), "x", "y")

            val exit = subscribe(kit, "expanding", "expansions")
                .mapConcatRecord { record ->
                    if (record.value() == "x") listOf(0) else listOf(1, 2, 3)
                }
                .mapParRecordOrFail(1) { n -> if (n == 3) fail(Refused("y")) else n }
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            exit shouldBe Exit.Failed(Refused("y"))
            withClue("x is committed; y's first two elements got through, and still y is not") {
                kit.broker.committed("expanding", Topic("expansions")) shouldBe 1L
            }
        }

        test("a body's log lines name the record it is handling") { kit ->
            kit.broker.send(Topic("annotated"), "only")

            val annotations = ConcurrentLinkedQueue<Map<String, String>>()
            val exit = subscribe(kit, "annotating", "annotated")
                .mapRecord { _ ->
                    capturingLogs { logs ->
                        logInfo("handled")
                        logs.all().single().annotations.also(annotations::add)
                    }
                }
                .take(1)
                .runCommitting(committer(kit))
                .run(kit.system)
                .settled()

            val named = mapOf("kafka.topic" to "annotated", "kafka.partition" to "0", "kafka.offset" to "0")
            exit shouldBe Exit.Done(Done.getInstance())
            annotations.toList() shouldBe listOf(named)
        }
    }
}
