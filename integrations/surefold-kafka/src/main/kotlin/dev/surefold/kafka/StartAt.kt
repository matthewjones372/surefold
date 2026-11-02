package dev.surefold.kafka

import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import java.time.Instant

/** Where [Kafka.consume] starts in each partition the group assigns it (spec 0159). */
sealed interface StartAt {
    /** After what the group committed; with nothing committed, where `auto.offset.reset` says. */
    data object Committed : StartAt

    /** After what the group committed; with nothing committed, at the first record written at or after [time]. */
    data class Time(val time: Instant) : StartAt

    /**
     * At the offset [offsets] gives for each partition, on every assignment, whatever the group committed: for a
     * group whose offsets live in the caller's own store. A partition it gives none for starts as [Committed] would.
     */
    class Own(val offsets: (Set<TopicPartition>) -> Map<TopicPartition, Long>) : StartAt
}

/** [consumer] moved to where [startAt] says for the newly assigned [partitions]; inside the assignment callback. */
internal fun KafkaConsumer<*, *>.startAt(startAt: StartAt, partitions: Set<TopicPartition>) {
    when (startAt) {
        StartAt.Committed -> Unit

        is StartAt.Time -> {
            val unstarted = committed(partitions).filterValues { it == null }.keys
            if (unstarted.isNotEmpty()) seekToTime(unstarted, startAt.time)
        }

        is StartAt.Own -> startAt.offsets(partitions).forEach { (partition, offset) ->
            if (partition in partitions) seek(partition, offset)
        }
    }
}

/** Each of [partitions] at its first record written at or after [time], or at its end where none was. */
internal fun KafkaConsumer<*, *>.seekToTime(partitions: Collection<TopicPartition>, time: Instant) {
    val found = offsetsForTimes(partitions.associateWith { time.toEpochMilli() })
    val none = partitions.filter { found[it] == null }
    found.forEach { (partition, at) -> at?.let { seek(partition, it.offset()) } }
    if (none.isNotEmpty()) seekToEnd(none)
}
