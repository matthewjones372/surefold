@file:OptIn(KafkaSpi::class)

package dev.surefold.kafka

import org.apache.kafka.clients.consumer.CloseOptions
import org.apache.kafka.clients.consumer.ConsumerGroupMetadata
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.errors.RetriableException
import org.apache.kafka.common.errors.WakeupException
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The offsets handled since a consumer last committed, and the committing of them: the one commit path every loop
 * over a consumer shares, whether its partitions come from a group or are assigned. Only the polling thread calls
 * [commit] and [close]; a [Handle] may be marked from any thread. A consumer with no group is not [committing]:
 * there is nowhere to commit to, and its handles are marked for nothing.
 */
internal class Commits(
    private val consumer: KafkaConsumer<*, *>,
    private val committing: Boolean = true,
    private val meters: ConsumerMeters = ConsumerMeters.none(),
) {

    /** The next offset to commit per partition: written by whichever thread ends the stream, read by the poller. */
    private val handled = Handled()

    /** The group as the last poll left it: read on the polling thread, and handed out with each record. */
    private var group: ConsumerGroupMetadata? = null

    /** The last offset handed out per partition: written by the polling thread, read in a revoke. */
    private val handedOut = ConcurrentHashMap<TopicPartition, Long>()

    /** Set once this consumer starts to leave its group, which a transaction still to commit has to know. */
    private val leaving = Leaving()

    /** After each poll: the group as it left it, for the records it returned. A consumer with no group has none. */
    fun polled() {
        if (committing) group = consumer.groupMetadata()
    }

    /** [record] with its position, and a handle that marks it handled here. */
    fun <K, V> carrying(record: ConsumerRecord<K, V>): Committed<ConsumerRecord<K, V>> {
        val partition = TopicPartition(record.topic(), record.partition())
        handedOut[partition] = record.offset()
        return Committed(
            record,
            Position(record.topic(), record.partition(), record.offset()),
            Offset(handled.marking(partition), record.offset(), group, leaving, meters.handing(record)),
        )
    }

    /**
     * Waits, as long as [rebalance] allows, until every record handed out on [partitions] is handled (spec 0158),
     * looking again at each mark: for a revoke, so what the stream still has in hand is committed rather than read
     * again. An interrupt does not end the wait, since the handling it waits for may be what set it off:
     * true if one came, for the caller to set again once it has committed.
     */
    fun handledOut(partitions: Set<TopicPartition>, rebalance: Rebalance): Boolean {
        if (!rebalance.within.isPositive()) return false
        val until = System.nanoTime() + rebalance.within.inWholeNanoseconds
        fun waiting() = partitions.any { partition ->
            handedOut[partition]?.let { last -> !handled.reached(partition, last + 1) } ?: false
        }
        var interrupted = false
        while (true) {
            // Drained before looking, so a mark made after the look leaves a permit to wake on.
            handled.advanced.drainPermits()
            val left = until - System.nanoTime()
            if (!waiting() || left <= 0) return interrupted
            try {
                handled.advanced.tryAcquire(left, TimeUnit.NANOSECONDS)
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
    }

    /** A refusal an async commit reported that sending again cannot fix, for the polling thread to throw. */
    private var refused: Exception? = null

    /**
     * What was handled on partitions this consumer still owns, sent without waiting for the broker: the commit
     * before each poll, which a poll would otherwise wait on. Its answer arrives in a later poll or commit, on
     * this thread. What the broker could not write for now stays due and goes with the next commit; a refusal
     * for good is thrown here, the next time, as a `commitSync` would have thrown it (spec 0154).
     */
    fun commitAsync() {
        refused?.let { failure ->
            refused = null
            throw failure
        }
        val due = handled.due(consumer.assignment())
        if (due.isEmpty() || !committing) return
        consumer.commitAsync(due.mapValues { (_, next) -> OffsetAndMetadata(next) }) { _, failure ->
            if (failure != null) meters.commitFailed()
            when (failure) {
                null -> due.forEach { (partition, next) -> handled.committed(partition, next) }
                is RetriableException -> Unit
                else -> refused = failure
            }
        }
    }

    /**
     * What was handled on partitions this consumer still owns, committed, and forgotten once it is. Waits for the
     * broker, and for any async commit still on its way: for a rebalance and a close, which must not lose them.
     */
    fun commit(owned: Set<TopicPartition> = consumer.assignment()): Boolean {
        val due = handled.due(owned)
        if (due.isEmpty() || !committing) return false
        val started = System.nanoTime()
        try {
            consumer.commitSync(due.mapValues { (_, next) -> OffsetAndMetadata(next) })
        } catch (failure: KafkaException) {
            meters.commitFailed()
            throw failure
        } finally {
            meters.committed(started)
        }
        // Only if nothing newer was handled meanwhile; a later offset is committed next time.
        due.forEach { (partition, next) -> handled.committed(partition, next) }
        return true
    }

    /** [partitions] lost to a rebalance: what was handled on them is dropped, and so is anything marked later. */
    fun forget(partitions: Collection<TopicPartition>) = partitions.forEach { partition ->
        handled.revoked(partition)
        handedOut.remove(partition)
    }

    /**
     * One lookup at the coordinator, timed, for a close with nothing to commit; null when it does not answer within
     * [LOOKUP_WITHIN] or fails, which leaves Kafka's own close. Only a measurement: it never fails the close. A member
     * with no partition has nothing to look up, and the client answers that without asking: null, as it measures
     * nothing.
     */
    private fun roundTrip(): Duration? {
        if (consumer.assignment().isEmpty()) return null
        val started = System.nanoTime()
        return try {
            try {
                consumer.committed(consumer.assignment(), LOOKUP_WITHIN)
            } catch (_: WakeupException) {
                consumer.committed(consumer.assignment(), LOOKUP_WITHIN)
            }
            since(started)
        } catch (_: KafkaException) {
            null
        }
    }

    /**
     * What was handled committed, then the consumer closed, however the commit went: within a few times the round
     * trip to its coordinator, which bounds the leave, rather than waiting behind a fetch the broker holds for
     * `fetch.max.wait.ms` (spec 0165). A commit that failed leaves no round trip, and Kafka's own close.
     */
    fun close() {
        val closing = System.nanoTime()
        // Waits for a transaction already sending this member's offsets: the member must still be there for it.
        leaving.start()
        // A take that ends a Forks run interrupts the fork reading the source, and that fork closes it: the
        // client would refuse both the commit and the close, and the last records handled be read again.
        val interrupted = Thread.interrupted()
        // Set once the commit has gone through: a close that follows a failed one is Kafka's own.
        var budget: Duration? = null
        try {
            val started = System.nanoTime()
            // A wake the loop never saw is still pending, and fails the first call that reaches the network.
            val sent = try {
                commit()
            } catch (_: WakeupException) {
                commit()
            }
            // Nothing was due: one lookup at the coordinator stands in for the commit's round trip.
            budget = if (sent || !committing) since(started) else roundTrip()
        } finally {
            try {
                budget?.let { within -> consumer.close(CloseOptions.timeout(within)) } ?: consumer.close()
            } finally {
                meters.closed(closing)
                if (interrupted) Thread.currentThread().interrupt()
            }
        }
    }
}

/** How long a close may take, from a round trip begun at [started]: [ROUND_TRIPS] of it, [CLOSE_FLOOR] at least. */
private fun since(started: Long): Duration =
    maxOf(Duration.ofNanos(System.nanoTime() - started).multipliedBy(ROUND_TRIPS), CLOSE_FLOOR)

private const val ROUND_TRIPS = 5L
private val LOOKUP_WITHIN: Duration = Duration.ofSeconds(1)
private val CLOSE_FLOOR: Duration = Duration.ofMillis(10)

/**
 * The next offset to commit per partition, and how often each was revoked. A record read before a revoke is the new
 * owner's to commit, and may reach the end of the stream after it, on Pekko more so since it reads ahead: its mark is
 * dropped rather than committed for a partition this consumer may be given back (spec 0154).
 */
private class Handled {
    private val next = ConcurrentHashMap<TopicPartition, Long>()
    private val revokes = ConcurrentHashMap<TopicPartition, Int>()

    /** The furthest mark per partition since its last revoke, committed or not: what a revoke waits to reach. */
    private val furthest = ConcurrentHashMap<TopicPartition, Long>()

    /** A permit once something was marked since a revoke last looked: at most one, however many marks. */
    val advanced = Semaphore(0)

    /** How a record read now marks [partition]: only while no revoke has come between. */
    fun marking(partition: TopicPartition): Marking {
        val readUnder = revokes[partition] ?: 0
        // In the map's lock for the partition, which a revoke's remove also takes, so a mark cannot land after it.
        return Marking { offset ->
            next.compute(partition) { _, was ->
                if ((revokes[partition] ?: 0) != readUnder) {
                    was
                } else {
                    furthest.merge(partition, offset, ::maxOf)
                    maxOf(was ?: offset, offset)
                }
            }
            if (advanced.availablePermits() == 0) advanced.release()
        }
    }

    fun due(owned: Set<TopicPartition>): Map<TopicPartition, Long> = next.filterKeys { it in owned }

    /** Whether [partition] has been marked up to [offset] since its last revoke. */
    fun reached(partition: TopicPartition, offset: Long): Boolean = (furthest[partition] ?: -1) >= offset

    /** Forgotten only if nothing newer was marked meanwhile; a later offset is committed next time. */
    fun committed(partition: TopicPartition, offset: Long) = next.remove(partition, offset)

    fun revoked(partition: TopicPartition) {
        revokes.merge(partition, 1, Int::plus)
        next.remove(partition)
        furthest.remove(partition)
    }
}

private fun interface Marking {
    fun mark(next: Long)
}

/** A record's offset, marked handled for its consumer to commit: the one after it is where the group resumes. */
private class Offset(
    private val marking: Marking,
    private val offset: Long,
    private val polledIn: ConsumerGroupMetadata?,
    private val leaving: Leaving,
    private val handling: Handling,
) : Handle {
    override fun handled() {
        marking.mark(offset + 1)
        handling.handled()
    }

    /**
     * The member that polled the record, so a transaction from a member that has since lost the partition is
     * refused. Once the consumer is leaving, the group alone: the member is gone, and a run's last transaction
     * commits after its source has ended.
     */
    // The group-id constructor is deprecated since Kafka 4.2 in favour of the consumer's own groupMetadata(),
    // which the broker refuses once the member has left, aborting every clean stop's last transaction (spec
    // 0125). Kafka 5 makes ConsumerGroupMetadata an interface, which this can implement with the group alone.
    @Suppress("DEPRECATION")
    override fun <T> withGroup(send: (ConsumerGroupMetadata?) -> T): T = leaving.holding { left ->
        send(polledIn?.let { member -> if (left) ConsumerGroupMetadata(member.groupId()) else member })
    }
}

/**
 * Whether a consumer has started to leave its group, under the lock a transaction holds while it sends offsets as
 * that member: the leave waits for the send, and a send after the leave goes with the group alone (spec 0131).
 */
private class Leaving {
    private val lock = ReentrantLock()
    private val left = AtomicBoolean(false)

    fun start() = lock.withLock { left.set(true) }

    fun <T> holding(body: (Boolean) -> T): T = lock.withLock { body(left.get()) }
}
