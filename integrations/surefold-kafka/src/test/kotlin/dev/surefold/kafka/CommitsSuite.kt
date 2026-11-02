package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import org.apache.kafka.common.serialization.StringSerializer
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** The one commit path every consumer loop shares, at the moment its consumer is closed. */
@OptIn(KafkaSpi::class)
object CommitsSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val GENEROUS_SECONDS = 30L
    private const val WAIT_MILLIS = 500L
    private const val HELD_FETCH_MILLIS = 3000
    private const val CLOSE_WITHIN_MILLIS = 1000L
    private const val POLL_MILLIS = 50L

    private fun consumer(kafka: KafkaBroker, group: String) = KafkaConsumer(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    )

    private fun KafkaConsumer<String, String>.polled(count: Int): List<ConsumerRecord<String, String>> {
        val read = ArrayList<ConsumerRecord<String, String>>()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
        while (read.size < count && System.nanoTime() < until) read += poll(Duration.ofMillis(100))
        return read
    }

    private fun producer(kafka: KafkaBroker, id: String) = KafkaProducer(
        mapOf<String, Any>(
            ProducerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
            ProducerConfig.TRANSACTIONAL_ID_CONFIG to id,
        ),
        StringSerializer(),
        StringSerializer(),
    ).also { it.initTransactions() }

    /** The offset after [records], as a transaction over them commits it. */
    private fun after(records: List<ConsumerRecord<String, String>>) =
        records.groupBy { TopicPartition(it.topic(), it.partition()) }
            .mapValues { (_, read) -> OffsetAndMetadata(read.maxOf { it.offset() } + 1) }

    /** A consumer that records which close it was asked for. */
    private class ClosingRecorded(bootstrap: String, group: String) : KafkaConsumer<String, String>(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ) {
        var closedWithin: Duration? = null
        var closedAsKafkaDoes = false

        override fun close() {
            closedAsKafkaDoes = true
            super.close()
        }

        override fun close(option: CloseOptions) {
            closedWithin = option.timeout().orElse(null)
            super.close(option)
        }
    }

    init {
        test(
            "a close on an interrupted thread still commits what was handled, and leaves the thread interrupted",
        ) { kafka ->
            val topic = "closed-interrupted"
            kafka.send(Topic(topic), "1", "2", "3")
            val consumer = consumer(kafka, topic).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            consumer.polled(3).forEach { record -> commits.carrying(record).handle?.handled() }

            // A take that ends a Forks run interrupts the fork reading the source, which then closes it.
            val stillInterrupted = CompletableFuture.supplyAsync {
                Thread.currentThread().interrupt()
                commits.close()
                Thread.currentThread().isInterrupted
            }.get(GENEROUS_SECONDS, TimeUnit.SECONDS)

            withClue("the close committed all three records, though its thread was interrupted") {
                kafka.committed(topic, Topic(topic)) shouldBe 3L
            }
            withClue("the interrupt is still there for whoever sent it") { stillInterrupted shouldBe true }
        }

        test(
            "a close waits for a transaction sending the member's offsets, which the broker takes from the member",
        ) { kafka ->
            val topic = "leaving-mid-send"
            kafka.send(Topic(topic), "1", "2", "3")
            val consumer = consumer(kafka, topic).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            val records = consumer.polled(3).also { commits.polled() }
            val handle = commits.carrying(records.last()).handle!!
            val sending = CountDownLatch(1)
            val send = CountDownLatch(1)

            producer(kafka, "leaving-mid-send").use { producer ->
                producer.beginTransaction()
                val generation = CompletableFuture.supplyAsync {
                    handle.withGroup { group ->
                        sending.countDown()
                        send.await(GENEROUS_SECONDS, TimeUnit.SECONDS)
                        producer.sendOffsetsToTransaction(after(records), group!!)
                        group.generationId()
                    }
                }
                sending.await(GENEROUS_SECONDS, TimeUnit.SECONDS) shouldBe true
                val closing = CompletableFuture.runAsync(commits::close)

                withClue("the consumer does not leave while a transaction is sending offsets as its member") {
                    shouldThrow<TimeoutException>({ closing.get(WAIT_MILLIS, TimeUnit.MILLISECONDS) })
                }
                send.countDown()
                withClue("the offsets went as the member, which was still in its group") {
                    (generation.get(GENEROUS_SECONDS, TimeUnit.SECONDS) > 0) shouldBe true
                }
                producer.commitTransaction()
                closing.get(GENEROUS_SECONDS, TimeUnit.SECONDS)
            }

            kafka.committed(topic, Topic(topic)) shouldBe 3L
        }

        test("a transaction after the close sends the group alone, which the broker takes") { kafka ->
            val topic = "sent-after-leaving"
            kafka.send(Topic(topic), "1", "2", "3")
            val consumer = consumer(kafka, topic).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            val records = consumer.polled(3).also { commits.polled() }
            val handle = commits.carrying(records.last()).handle!!
            commits.close()

            producer(kafka, "sent-after-leaving").use { producer ->
                producer.beginTransaction()
                handle.withGroup { group ->
                    withClue("the member has gone, so no generation goes with the offsets") {
                        group!!.generationId() shouldBe -1
                    }
                    producer.sendOffsetsToTransaction(after(records), group)
                }
                producer.commitTransaction()
            }

            kafka.committed(topic, Topic(topic)) shouldBe 3L
        }

        test(
            "a record handled after its partition was revoked commits nothing, and one read after it is back does",
        ) { kafka ->
            val topic = "handled-after-revoke"
            kafka.send(Topic(topic), "1", "2", "3")
            val consumer = consumer(kafka, topic).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            val records = consumer.polled(3)
            val late = records.map { commits.carrying(it).handle!! }
            val partition = TopicPartition(topic, 0)

            // What a rebalance does to a partition this consumer loses, while its records are still in the stream.
            commits.forget(listOf(partition))
            late.forEach { it.handled() }
            commits.commit()
            withClue("records read before the revoke are the new owner's to commit") {
                kafka.committed(topic, Topic(topic)) shouldBe null
            }

            commits.carrying(records.last()).handle!!.handled()
            commits.close()
            withClue("a record read once the partition is this consumer's again commits as usual") {
                kafka.committed(topic, Topic(topic)) shouldBe 3L
            }
        }

        test(
            "a close does not wait out the fetch the broker is holding, and still leaves the group with its offsets",
        ) { kafka ->
            val topic = "closed-past-a-held-fetch"
            kafka.send(Topic(topic), "1", "2", "3")
            // A broker that holds an empty fetch for 3s, so a close that waits for it is plain to see.
            val consumer = KafkaConsumer(
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                    ConsumerConfig.GROUP_ID_CONFIG to topic,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                    ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to HELD_FETCH_MILLIS,
                ),
                StringDeserializer(),
                StringDeserializer(),
            ).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            consumer.polled(3).forEach { record -> commits.carrying(record).handle?.handled() }
            // The read ahead that a take or a stop interrupts: its fetch is at the broker, held.
            consumer.poll(Duration.ofMillis(POLL_MILLIS))

            val started = System.nanoTime()
            commits.close()
            val closing = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

            withClue("the close took ${closing}ms against a fetch held for ${HELD_FETCH_MILLIS}ms") {
                (closing < CLOSE_WITHIN_MILLIS) shouldBe true
            }
            kafka.committed(topic, Topic(topic)) shouldBe 3L
            withClue("the consumer left its group rather than timing out of it") {
                kafka.members(topic) shouldBe 0
            }
        }

        test(
            "a member that was given no partition closes as Kafka does, rather than within a lookup it never made",
        ) { kafka ->
            val topic = "more-members-than-partitions"
            kafka.send(Topic(topic), "1")
            consumer(kafka, topic).use { owner ->
                owner.subscribe(listOf(topic))
                owner.polled(1)
                val idle = ClosingRecorded(kafka.bootstrap, topic).also { it.subscribe(listOf(topic)) }
                val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
                while (kafka.members(topic) < 2 && System.nanoTime() < until) {
                    idle.poll(Duration.ofMillis(POLL_MILLIS))
                    owner.poll(Duration.ofMillis(POLL_MILLIS))
                }
                withClue("the second member joined, and the topic's one partition stayed with the first") {
                    kafka.members(topic) shouldBe 2
                    idle.assignment() shouldBe emptySet()
                }

                Commits(idle).close()

                // Looking up an empty assignment never reaches the coordinator, so it measures nothing to close within.
                withClue("closed within ${idle.closedWithin}") { idle.closedAsKafkaDoes shouldBe true }
                // Within the 30s the test allows, short of the 45s session timeout, so the member left rather
                // than expired.
                val left = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
                while (kafka.members(topic) > 1 && System.nanoTime() < left) owner.poll(Duration.ofMillis(POLL_MILLIS))
                withClue("the member left its group") { kafka.members(topic) shouldBe 1 }
            }
        }

        test(
            "a close with nothing due and a wake still pending closes cleanly, rather than throwing the wake",
        ) { kafka ->
            val topic = "closed-with-a-wake-pending"
            kafka.send(Topic(topic), "1")
            val consumer = consumer(kafka, topic).also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            // Joined, with nothing handled: there is nothing for the close to commit.
            consumer.polled(1)
            // A stop that came after the poll it was meant to wake: the wake is still pending.
            consumer.wakeup()

            commits.close()

            withClue("the consumer left its group") { kafka.members(topic) shouldBe 0 }
        }

        test(
            "a coordinator that does not answer the close's lookup leaves Kafka's own close, rather than a failed stop",
        ) { kafka ->
            val topic = "closed-with-no-coordinator"
            kafka.send(Topic(topic), "1")
            // A coordinator that will not answer: the lookup that stands in for a commit's round trip times out.
            val consumer = object : KafkaConsumer<String, String>(
                mapOf<String, Any>(
                    ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap,
                    ConsumerConfig.GROUP_ID_CONFIG to topic,
                    ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
                    ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
                ),
                StringDeserializer(),
                StringDeserializer(),
            ) {
                override fun committed(partitions: Set<TopicPartition>): Map<TopicPartition, OffsetAndMetadata> =
                    throw org.apache.kafka.common.errors.TimeoutException("the coordinator did not answer")

                override fun committed(
                    partitions: Set<TopicPartition>,
                    timeout: Duration,
                ): Map<TopicPartition, OffsetAndMetadata> =
                    throw org.apache.kafka.common.errors.TimeoutException("the coordinator did not answer")
            }.also { it.subscribe(listOf(topic)) }
            val commits = Commits(consumer)
            // Joined, with nothing handled: the close has nothing to commit, so it asks instead.
            consumer.polled(1)

            commits.close()

            withClue("the consumer left its group") { kafka.members(topic) shouldBe 0 }
        }
    }
}
