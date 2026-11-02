package dev.surefold.kafka

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.Metric
import org.apache.kafka.common.MetricName
import org.apache.kafka.common.TopicPartition
import java.util.concurrent.atomic.AtomicReference

/**
 * What a running [Kafka.consume] can be asked (spec 0160): passed in as its `questions`, and answered by whichever
 * run holds it. Each answer is read from the run's own consumer between polls, never while another thread uses it,
 * so a question waits for the poll under way, at most `pollTimeout`. Asked while no run holds it, it throws.
 */
class ConsumerQuestions {
    private val run = AtomicReference<Answering?>()

    /** The partitions the group has assigned to the run. */
    fun assignment(): Set<TopicPartition> = ask { consumer -> consumer.assignment().toSet() }

    /**
     * How far behind each assigned partition the group is: its end offset less what the group committed, or less its
     * beginning where the group has committed nothing.
     */
    fun lag(): Map<TopicPartition, Long> = ask { consumer ->
        val owned = consumer.assignment()
        val ends = consumer.endOffsets(owned)
        val committed = consumer.committed(owned)
        val starts = consumer.beginningOffsets(owned)
        owned.associateWith { partition ->
            ends.getValue(partition) - (committed[partition]?.offset() ?: starts.getValue(partition))
        }
    }

    /** The client's own metrics, all of them: spec 0163's meters record a few, and this has the rest. */
    fun metrics(): Map<MetricName, Metric> = ask { consumer -> consumer.metrics() }

    private fun <T> ask(question: (KafkaConsumer<*, *>) -> T): T =
        checkNotNull(run.get()) { "no run of Kafka.consume holds these questions" }.answer(question)

    /** [answering] answers from now on; a second run open on the same questions is refused. */
    internal fun held(answering: Answering) =
        check(run.compareAndSet(null, answering)) { "these questions are already held by a run of Kafka.consume" }

    internal fun letGo(answering: Answering) {
        run.compareAndSet(answering, null)
    }
}

/** A run's consumer, lent to a question for as long as it takes to answer. */
internal interface Answering {
    fun <T> answer(question: (KafkaConsumer<*, *>) -> T): T
}
