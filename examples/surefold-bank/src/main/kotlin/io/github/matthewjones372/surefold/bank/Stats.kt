package io.github.matthewjones372.surefold.bank

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.actor.Topic
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.onStart
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.DoubleAdder
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.DurationUnit

/** Transfers that ended on a node, tagged by `outcome`, and how long each took from its start, in milliseconds. */
internal const val ENDED = "bank.transfers.ended"
internal const val TOOK = "bank.transfers.ms"

/** One node's scope's metrics, as surefold records them, kept in this process for the sampler to read. */
internal class Recorder : Metrics {
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()
    private val gauges = ConcurrentHashMap<Pair<String, Map<String, String>>, Double>()
    private val took = ConcurrentLinkedQueue<Double>()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counters.computeIfAbsent(name to tags) { DoubleAdder() }
        return Counter(adder::add)
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { gauges[name to tags] = it }

    override fun histogram(name: String, tags: Map<String, String>): Histogram =
        Histogram { if (name == TOOK) took += it }

    /** Every count of [name] so far, over all its tags that [tagged] accepts. */
    fun total(name: String, tagged: (Map<String, String>) -> Boolean = { true }): Double =
        counters.entries.filter { (key) -> key.first == name && tagged(key.second) }.sumOf { it.value.sum() }

    /** The latest of each gauge named [name], summed over its tags. */
    fun gauged(name: String): Double = gauges.entries.filter { (key) -> key.first == name }.sumOf { it.value }

    /** Every time taken since the last call. */
    fun drainTook(): List<Double> = generateSequence { took.poll() }.toList()
}

/** What one node publishes each second on `bank-stats`: what it did in that second, and what it holds now. */
@Serializable
internal data class NodeStats(
    val node: String,
    val at: String,
    val transfersPerSecond: Long,
    val refused: Long,
    val p99Ms: Long,
    val shards: Long,
    val entities: Long,
    val unconfirmed: Long,
    val deadLetters: Long,
) {
    fun json() = json.encodeToString(this)
}

/** What happened in the cluster, on `bank-events`: a member changed, or a transfer ended. */
internal sealed interface BankEvent {
    @Serializable
    data class Member(val node: String, val status: String) : BankEvent

    @Serializable
    data class Ended(
        val id: String,
        val from: String,
        val to: String,
        @SerialName("amount") val pence: Long,
        val outcome: String,
        val ms: Long,
    ) : BankEvent
}

internal fun BankEvent.event(): Event = when (this) {
    is BankEvent.Member -> Event("member", json.encodeToString(this))
    is BankEvent.Ended -> Event("transfer", json.encodeToString(this))
}

internal data object Sample

internal data class Totals(val ended: Double, val refused: Double)

/** Samples [recorder] every [every] and publishes what it finds as [node]'s stats on [stats]. */
internal fun sampler(node: String, recorder: Recorder, stats: Topic<NodeStats>, every: Duration) =
    behaviour<Sample, Totals>(Totals(0.0, 0.0)) { _, before, _ ->
        val ended = recorder.total(ENDED)
        val refused = recorder.total(ENDED) { it["outcome"] == Phase.Refused.name }
        val took = recorder.drainTook().sorted()
        val seconds = every.toDouble(DurationUnit.SECONDS)
        fun perSecond(now: Double, then: Double) = ((now - then) / seconds).roundToLong()
        stats.publish(
            NodeStats(
                node = node,
                at = Instant.now().toString(),
                transfersPerSecond = perSecond(ended, before.ended),
                refused = perSecond(refused, before.refused),
                p99Ms =
                took.getOrNull((took.size * P99).toInt())?.roundToLong() ?: took.lastOrNull()?.roundToLong() ?: 0,
                shards = recorder.gauged("surefold.sharding.shards").roundToLong(),
                entities = recorder.gauged("surefold.sharding.entities").roundToLong(),
                unconfirmed = recorder.gauged("surefold.delivery.unconfirmed").roundToLong(),
                deadLetters = recorder.total("surefold.actor.dead_letters").roundToLong(),
            ),
        )
        become(Totals(ended, refused))
    }.onStart { ctx -> ctx.timers.every(Sample, every, Sample) }

private const val P99 = 0.99

/** Hears both topics and hands each message to this node's browsers as an event; stats carry an id, counting up. */
internal fun dashboard(hub: Hub) = behaviour<Any, Long>(0L) { _, sent, message ->
    when (message) {
        is NodeStats -> hub.publish(Event(STATS, message.json(), id = sent + 1))
        is BankEvent -> hub.publish(message.event())
    }
    become(sent + 1)
}

internal object NodeStatsCodec : MessageCodec<NodeStats> {
    override fun write(message: NodeStats, out: WireOut) {
        out.string(message.node)
        out.string(message.at)
        listOf(
            message.transfersPerSecond, message.refused, message.p99Ms, message.shards, message.entities,
            message.unconfirmed, message.deadLetters,
        ).forEach(out::long)
    }

    override fun read(input: WireIn): NodeStats = NodeStats(
        input.string(), input.string(), input.long(), input.long(), input.long(), input.long(), input.long(),
        input.long(), input.long(),
    )
}

internal object BankEventCodec : MessageCodec<BankEvent> {
    override fun write(message: BankEvent, out: WireOut) = when (message) {
        is BankEvent.Member -> {
            out.int(MEMBER)
            out.string(message.node)
            out.string(message.status)
        }

        is BankEvent.Ended -> {
            out.int(ENDED_TAG)
            listOf(message.id, message.from, message.to).forEach(out::string)
            out.long(message.pence)
            out.string(message.outcome)
            out.long(message.ms)
        }
    }

    override fun read(input: WireIn): BankEvent = when (val tag = input.int()) {
        MEMBER -> BankEvent.Member(input.string(), input.string())
        ENDED_TAG -> input.run { BankEvent.Ended(string(), string(), string(), long(), string(), long()) }
        else -> error("no bank event has the tag $tag")
    }

    private const val MEMBER = 1
    private const val ENDED_TAG = 2
}
