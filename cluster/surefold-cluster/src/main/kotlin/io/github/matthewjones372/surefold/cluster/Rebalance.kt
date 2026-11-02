package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import java.util.concurrent.atomic.AtomicIntegerArray
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * How a sharded kind's shards follow the load (spec 0090). Every [every], each member gossips what it runs, and the
 * leader moves up to [mostMoves] of the busiest member's shards to the least-loaded members while it is over the mean
 * by more than [tolerance]. Load is messages handled where there are any, and running entities otherwise. A shard
 * moved is not moved again for three intervals.
 */
class Rebalance internal constructor(val every: Duration, val tolerance: Double, val mostMoves: Int) {
    init {
        require(every.isPositive()) { "every must be positive, was $every" }
        require(tolerance >= 0.0) { "tolerance must not be negative, was $tolerance" }
        require(mostMoves > 0) { "mostMoves must be positive, was $mostMoves" }
    }

    companion object {
        fun byLoad(every: Duration = 1.minutes, tolerance: Double = 0.2, mostMoves: Int = 4): Rebalance =
            Rebalance(every, tolerance, mostMoves)
    }
}

/**
 * The load of kinds that rebalance, as the gossip has it: per live member, kind and shard; and per kind, the shards
 * the leader has moved, and to which life of a member.
 */
internal data class Balance(
    val loads: Map<Node, Map<String, Map<Int, ShardLoad>>>,
    val moved: Map<String, Map<Int, Incarnation>> = emptyMap(),
) {
    companion object {
        val None = Balance(emptyMap())
    }
}

/** What one kind runs here, per shard: counted by its entities and its region, and read by the cluster actor. */
internal class LoadMeter(val kind: String, val rebalance: Rebalance, shards: Int, val role: String?) {
    private val running = AtomicIntegerArray(shards)
    private val handled = AtomicIntegerArray(shards)

    // Read and written only by the cluster actor.
    private var next = Duration.ZERO

    fun running(shard: Int, delta: Int) {
        running.addAndGet(shard, delta)
    }

    fun handled(shard: Int) {
        handled.incrementAndGet(shard)
    }

    /** The load of each shard with any, if an interval has passed since the last; messages count again from none. */
    fun due(now: Duration): Map<Int, ShardLoad>? {
        if (now < next) return null
        next = now + rebalance.every
        return (0 until running.length()).associateWith { ShardLoad(running[it], handled.getAndSet(it, 0)) }
            .filterValues { it.entities > 0 || it.messages > 0 }
    }
}

/**
 * The leader's side of rebalancing: every kind's moves once per interval, while this node leads. A shard it moves is
 * held for three intervals: not moved again, and counted at no less than the load it moved with, since its new owner's
 * first reports see only part of it. A node that has just come to lead waits an interval, and holds every shard
 * already moved.
 */
internal class Balancer {
    private val held = HashMap<Pair<String, Int>, Pair<Duration, ShardLoad>>()
    private val due = HashMap<String, Duration>()
    private var leading = false

    fun moves(now: Duration, meters: List<LoadMeter>, view: View, self: Node, balance: Balance) =
        if (!leads(now, view.leader == self, balance)) {
            emptyMap()
        } else {
            meters.filter { now >= due.getOrPut(it.kind) { now + it.rebalance.every } }.associate { meter ->
                due[meter.kind] = now + meter.rebalance.every
                meter.kind to move(now, meter, view.members.holding(meter.role), balance)
            }
        }

    private fun move(now: Duration, meter: LoadMeter, members: List<Member>, balance: Balance): Map<Int, Node?> {
        val hosts = members.filter { it.status == Status.Up }
        val window = meter.rebalance.every * 3
        held.entries.removeIf { (key, at) -> key.first == meter.kind && now - at.first >= window }
        val holding = held.entries.filter { it.key.first == meter.kind }.associate { it.key.second to it.value.second }
        val reported = hosts.mapNotNull { balance.loads[it.node]?.get(meter.kind) } + holding
        val loads = reported.flatMap { it.entries }.groupBy({ it.key }, { it.value }).mapValues { (_, all) ->
            ShardLoad(all.maxOf { it.entities }, all.maxOf { it.messages })
        }
        val moved = balance.moved[meter.kind].orEmpty()
        return meter.rebalance.propose(meter.kind, hosts, loads, moved, holding.keys).onEach { (shard, _) ->
            held[meter.kind to shard] = now to loads.getValue(shard)
        }
    }

    private fun leads(now: Duration, leader: Boolean, balance: Balance): Boolean {
        if (leader && !leading) {
            due.clear()
            balance.moved.forEach { (kind, moved) -> moved.keys.forEach { held[kind to it] = now to ShardLoad(0, 0) } }
        }
        leading = leader
        return leader
    }
}

/**
 * The moves that bring the busiest of [hosts] towards the mean: its heaviest shards but those [held] first, each to
 * the least-loaded member if that leaves it less loaded than the busiest. Each shard counts on its owner now.
 */
internal fun Rebalance.propose(
    kind: String,
    hosts: List<Member>,
    loads: Map<Int, ShardLoad>,
    moved: Map<Int, Incarnation>,
    held: Set<Int>,
): Map<Int, Node?> {
    val byMessages = loads.values.any { it.messages > 0 }
    val load = loads.mapValues { (_, it) -> if (byMessages) it.messages else it.entities }
    val owned = load.keys.groupBy { Placement.owner(kind, it, hosts, moved[it]) }
    val total = hosts.associateTo(HashMap()) { m -> m.node to owned[m.node].orEmpty().sumOf(load::getValue) }
    val busiest = total.entries.maxWithOrNull(compareBy({ it.value }, { it.key.toString() }))?.key
    if (hosts.size < 2 || busiest == null) return emptyMap()
    val limit = total.values.sum().toDouble() / hosts.size * (1 + tolerance)
    val moves = LinkedHashMap<Int, Node?>()
    owned[busiest].orEmpty().filterNot(held::contains).sortedWith(compareBy({ -load.getValue(it) }, { it }))
        .forEach { shard ->
            val weight = load.getValue(shard)
            val to = total.entries.minWith(compareBy({ it.value }, { it.key.toString() })).key
            val over = total.getValue(busiest) > limit && moves.size < mostMoves
            if (over && weight > 0 && total.getValue(to) + weight < total.getValue(busiest)) {
                moves[shard] = to.takeIf { Placement.owner(kind, shard, hosts) != it }
                total[busiest] = total.getValue(busiest) - weight
                total[to] = total.getValue(to) + weight
            }
        }
    return moves
}
