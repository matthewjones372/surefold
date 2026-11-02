package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import org.apache.kafka.clients.consumer.CommitFailedException
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.clients.consumer.OffsetCommitCallback
import org.apache.kafka.clients.consumer.RetriableCommitFailedException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.StringDeserializer
import java.time.Duration
import java.util.concurrent.TimeUnit

/** The commit a consumer loop makes before each poll, which the poll does not wait for (spec 0154). */
@OptIn(KafkaSpi::class)
object CommitBeforePollSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    private const val GENEROUS_SECONDS = 30L

    /** A consumer that records which commit it was asked for, and can answer an async one with a failure. */
    private class Recording(bootstrap: String, group: String) : KafkaConsumer<String, String>(
        mapOf<String, Any>(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to bootstrap,
            ConsumerConfig.GROUP_ID_CONFIG to group,
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG to "earliest",
            ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG to false,
        ),
        StringDeserializer(),
        StringDeserializer(),
    ) {
        val asked = mutableListOf<String>()

        /** Failures to answer the next async commits with, in place of sending them. */
        val failing = ArrayDeque<Exception>()

        override fun commitSync(offsets: Map<TopicPartition, OffsetAndMetadata>) {
            asked += "sync"
            super.commitSync(offsets)
        }

        override fun commitAsync(offsets: Map<TopicPartition, OffsetAndMetadata>, callback: OffsetCommitCallback?) {
            asked += "async"
            val failure = failing.removeFirstOrNull()
            if (failure == null) super.commitAsync(offsets, callback) else callback?.onComplete(offsets, failure)
        }

        fun polled(count: Int): List<ConsumerRecord<String, String>> {
            val read = ArrayList<ConsumerRecord<String, String>>()
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(GENEROUS_SECONDS)
            while (read.size < count && System.nanoTime() < until) read += poll(Duration.ofMillis(100))
            return read
        }
    }

    private fun handled(kafka: KafkaBroker, topic: String): Pair<Recording, Commits> {
        kafka.send(Topic(topic), "1", "2", "3")
        val consumer = Recording(kafka.bootstrap, topic).also { it.subscribe(listOf(topic)) }
        val commits = Commits(consumer)
        consumer.polled(3).forEach { record -> commits.carrying(record).handle?.handled() }
        return consumer to commits
    }

    init {
        test("the commit before a poll is sent without waiting, and lands by the time the loop closes") { kafka ->
            val (consumer, commits) = handled(kafka, "sent-before-poll")

            commits.commitAsync()
            withClue("the commit before a poll does not wait on the broker") {
                consumer.asked shouldBe listOf("async")
            }

            consumer.poll(Duration.ofMillis(100))
            commits.close()
            withClue("the close committed what the async commit had sent, and waited for it") {
                kafka.committed("sent-before-poll", Topic("sent-before-poll")) shouldBe 3L
            }
        }

        test("an offset an async commit could not write for now is sent again by the next one") { kafka ->
            val (consumer, commits) = handled(kafka, "retried-before-poll")
            consumer.failing += RetriableCommitFailedException("the coordinator moved")

            commits.commitAsync()
            commits.commitAsync()
            consumer.poll(Duration.ofMillis(100))
            consumer.asked shouldBe listOf("async", "async")

            commits.close()
            kafka.committed("retried-before-poll", Topic("retried-before-poll")) shouldBe 3L
        }

        test("an async commit refused for good fails the next commit, on the polling thread") { kafka ->
            val (consumer, commits) = handled(kafka, "refused-before-poll")
            val refused = CommitFailedException("the member was removed from its group")
            consumer.failing += refused

            commits.commitAsync()
            withClue("the refusal surfaces where a commitSync throw would have") {
                shouldThrow<CommitFailedException>({ commits.commitAsync() }) { thrown -> thrown shouldBe refused }
            }
            consumer.close()
        }
    }
}
