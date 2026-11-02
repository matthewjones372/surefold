package io.github.matthewjones372.surefold.kafka

import dev.surefold.Schedule
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.right
import dev.surefold.retryOrElse
import org.apache.kafka.clients.admin.Admin
import org.apache.kafka.clients.admin.ListGroupsOptions
import org.apache.kafka.clients.admin.NewPartitions
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.admin.OffsetSpec
import org.apache.kafka.clients.admin.TopicDescription
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.KafkaException
import org.apache.kafka.common.KafkaFuture
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.config.ConfigResource
import org.apache.kafka.common.errors.TopicExistsException
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException
import java.util.Optional
import java.util.concurrent.ExecutionException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The few `Admin` calls a service's start-up and a team's runbooks make (spec 0162), each blocking until the cluster
 * answers. The failures a caller acts on are values; anything else the cluster answers is thrown, as a defect.
 * Whoever opens it closes it.
 */
class KafkaAdmin internal constructor(private val admin: Admin) : AutoCloseable {

    /**
     * [topic] made to exist with at least [partitions] partitions: created with them, [replicas] copies of each (the
     * broker's default when null) and [config], or grown to them if it has fewer. It never shrinks a topic, which
     * Kafka cannot do, and refuses to try. An existing topic's config is left as it is; how it differs from [config]
     * comes back for the caller to act on.
     */
    fun ensure(
        topic: Topic,
        partitions: Int,
        replicas: Int? = null,
        config: Map<String, String> = emptyMap(),
    ): Ensured {
        require(partitions > 0) { "a topic needs a partition, and ${topic.name} was asked for $partitions" }
        val created = try {
            val asked = NewTopic(topic.name, Optional.of(partitions), Optional.ofNullable(replicas?.toShort()))
            admin.createTopics(listOf(asked.configs(config))).all().answer()
            true
        } catch (_: TopicExistsException) {
            false
        }
        if (created) return Ensured(created = true, grownFrom = null, differing = emptyMap())

        val described = settled { admin.describeTopics(listOf(topic.name)).allTopicNames().answer() }
        val had = described.getValue(topic.name).partitions().size
        require(had <= partitions) { "${topic.name} has $had partitions, and Kafka cannot shrink it to $partitions" }
        if (had < partitions) {
            admin.createPartitions(mapOf(topic.name to NewPartitions.increaseTo(partitions))).all().answer()
        }
        val grownFrom = had.takeIf { it < partitions }
        return Ensured(created = false, grownFrom = grownFrom, differing = differing(topic, config))
    }

    /** Every topic on the cluster but Kafka's own internal ones. */
    fun topics(): Set<Topic> = admin.listTopics().names().answer().map(::Topic).toSet()

    /** [topic]'s partitions, leaders and replicas. */
    fun describe(topic: Topic): Either<NoSuchTopic, TopicDescription> = existing(topic) {
        admin.describeTopics(listOf(topic.name)).allTopicNames().answer().getValue(topic.name)
    }

    /** [topic] deleted, records and all. */
    fun delete(topic: Topic): Either<NoSuchTopic, Unit> = existing(topic) {
        admin.deleteTopics(listOf(topic.name)).all().answer()
    }.map { }

    /** Every consumer group the cluster knows, by id. */
    fun groups(): Set<String> =
        admin.listGroups(ListGroupsOptions.forConsumerGroups()).all().answer().map { it.groupId() }.toSet()

    /** How far behind each partition [group] has committed for it is: its end offset less the group's commit. */
    fun lag(group: String): Map<TopicPartition, Long> {
        val committed = admin.listConsumerGroupOffsets(group).partitionsToOffsetAndMetadata().answer()
            .filterValues { it != null }
        val ends = offsets(committed.keys, OffsetSpec.latest())
        return committed.mapValues { (partition, at) -> ends.getValue(partition) - at.offset() }
    }

    /**
     * [group]'s offsets on every partition of [topic] moved to where [to] says, for its next run to start there: a
     * runbook's replay. Kafka allows it only while the group has no members, so a group still running is [GroupActive].
     * [From.Time] moves a partition with no record at or after the time to its end; [From.Committed] is no place to
     * move to, and is refused.
     */
    fun resetOffsets(group: String, topic: Topic, to: From): Either<GroupActive, Unit> {
        require(to != From.Committed) { "resetting $group to where it committed would leave it where it is" }
        val members = admin.describeConsumerGroups(listOf(group)).all().answer().getValue(group).members()
        if (members.isNotEmpty()) return GroupActive(group, members.size).left()
        val described = admin.describeTopics(listOf(topic.name)).allTopicNames().answer().getValue(topic.name)
        val partitions = described.partitions().map { TopicPartition(topic.name, it.partition()) }.toSet()
        val moved = when (to) {
            From.Earliest -> offsets(partitions, OffsetSpec.earliest())

            From.Latest -> offsets(partitions, OffsetSpec.latest())

            is From.Offset -> partitions.associateWith { to.offset }

            is From.Time -> {
                val ends = offsets(partitions, OffsetSpec.latest())
                offsets(partitions, OffsetSpec.forTimestamp(to.time.toEpochMilli()))
                    .mapValues { (partition, at) -> if (at < 0) ends.getValue(partition) else at }
            }

            From.Committed -> error("refused above")
        }
        admin.alterConsumerGroupOffsets(group, moved.mapValues { (_, at) -> OffsetAndMetadata(at) }).all().answer()
        return Unit.right()
    }

    override fun close() = admin.close()

    /** Each of [partitions]' offset as [spec] asks, from the partition leaders. */
    private fun offsets(partitions: Set<TopicPartition>, spec: OffsetSpec): Map<TopicPartition, Long> =
        admin.listOffsets(partitions.associateWith { spec }).all().answer().mapValues { (_, at) -> at.offset() }

    /** Each key of [config] whose value on [topic] is not the one asked, with the topic's own value. */
    private fun differing(topic: Topic, config: Map<String, String>): Map<String, String?> {
        if (config.isEmpty()) return emptyMap()
        val resource = ConfigResource(ConfigResource.Type.TOPIC, topic.name)
        val own = settled { admin.describeConfigs(listOf(resource)).all().answer() }.getValue(resource)
        return config.mapValues { (key, _) -> own.get(key)?.value() }.filter { (key, value) -> value != config[key] }
    }

    private fun <A> existing(topic: Topic, block: () -> A): Either<NoSuchTopic, A> = try {
        block().right()
    } catch (_: UnknownTopicOrPartitionException) {
        NoSuchTopic(topic).left()
    }
}

/** An admin client on the cluster [properties] name, for the caller to close. */
fun Kafka.admin(properties: Map<String, Any>): KafkaAdmin = KafkaAdmin(Admin.create(properties))

/** What [KafkaAdmin.ensure] did: created the topic, grew it from [grownFrom] partitions, or neither. */
data class Ensured(val created: Boolean, val grownFrom: Int?, val differing: Map<String, String?>)

/** A topic the cluster does not have. */
data class NoSuchTopic(val topic: Topic)

/** A group that still has [members], whose offsets Kafka will not let anyone else move. */
data class GroupActive(val group: String, val members: Int)

/**
 * [block] for a topic the controller has, just created or found existing, tried again while the broker it asks has yet
 * to hear of it: its metadata follows the controller's by a moment. Any other failure is thrown at once.
 */
private fun <A> settled(block: () -> A): A = SETTLING.retryOrElse({
    try {
        block().right()
    } catch (unheard: UnknownTopicOrPartitionException) {
        throw unheard
    } catch (other: KafkaException) {
        other.left()
    }
}) { failure, _ -> throw failure }.fold({ throw it }, { it })

/** How long [settled] waits for a broker to hear of a topic, and how often it asks. */
private val SETTLING = Schedule.spaced<Throwable>(50.milliseconds) and Schedule.upTo(10.seconds)

/** The cluster's answer, or what it failed with, unwrapped from the future's `ExecutionException`. */
private fun <A> KafkaFuture<A>.answer(): A = try {
    get()
} catch (failed: ExecutionException) {
    throw failed.cause ?: failed
}
