package dev.surefold.cluster

import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.Node
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldHaveSize
import dev.surefold.junit.withClue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

private val three = (1..3).map { Member(Node("m$it", "10.0.0.$it", 25520), it.toLong(), Status.Up, it) }

private fun Member.life() = Incarnation(node, uid)

/** The first [count] shards of "k" that [member] owns by hash. */
private fun ownedBy(member: Member, count: Int) =
    (0 until Sharding.SHARDS).filter { Placement.owner("k", it, three) == member.node }.take(count)

/** Each member's load of "k", every shard counted on the member that owns it now. */
private fun totals(load: Map<Int, Int>, moved: Map<Int, Incarnation>) = three.associate { m ->
    m.node to load.filterKeys { Placement.owner("k", it, three, moved[it]) == m.node }.values.sum()
}

/** A balance in which every shard's owner reports [load] of entities for it. */
private fun balance(load: Map<Int, Int>, moved: Map<Int, Incarnation>) = Balance(
    three.associate { m ->
        val mine = load.filterKeys { Placement.owner("k", it, three, moved[it]) == m.node }
        m.node to mapOf("k" to mine.mapValues { ShardLoad(it.value, 0) })
    },
    mapOf("k" to moved),
)

/** Applies [moves] as the leader's gossip would: a null move sends the shard back to its hash owner. */
private fun Map<Int, Incarnation>.after(moves: Map<Int, Node?>) = moves.entries.fold(this) { moved, (shard, to) ->
    if (to == null) moved - shard else moved + (shard to three.first { it.node == to }.life())
}

private val rebalance = Rebalance.byLoad(every = 1.minutes, tolerance = 0.2, mostMoves = 4)
private val led = View(three, emptySet(), three[0].node)

// Read for its kind, rebalance and role only: the balancer never asks it for its load.
private val meter = LoadMeter("k", rebalance, Sharding.SHARDS, role = null)

private fun Balancer.round(at: Duration, balance: Balance) =
    moves(at, listOf(meter), led, three[0].node, balance)["k"].orEmpty()

object RebalanceSuite : Suite() {
    init {
        test("the busiest member falls within tolerance of the mean within three intervals") {
            val load = ownedBy(three[0], 12).associateWith { 10 }
            val balancer = Balancer()
            var moved = emptyMap<Int, Incarnation>()
            balancer.round(Duration.ZERO, balance(load, moved)).shouldBeEmpty()

            val rounds = (1..3).map { round ->
                balancer.round(rebalance.every * round, balance(load, moved)).also { moved = moved.after(it) }
            }

            withClue("no round moves more than mostMoves") {
                rounds.forAll { it.size shouldBeLessThanOrEqual 4 }
            }
            val totals = totals(load, moved)
            totals.values.max().toDouble() shouldBeLessThanOrEqual totals.values.sum() / 3.0 * 1.2
        }

        test("a moved shard is not moved again within three intervals, even where it would be the one to move") {
            val (a, b, c) = ownedBy(three[0], 3)
            val e = ownedBy(three[1], 1).single()
            val g = ownedBy(three[2], 1).single()
            val balancer = Balancer()
            balancer.round(Duration.ZERO, Balance.None).shouldBeEmpty()

            val first = balancer.round(rebalance.every, balance(mapOf(a to 10, b to 10, c to 10, g to 10), emptyMap()))
            first shouldBe mapOf(a to three[1].node)
            // The load grew where the shard went, so that it is now the busiest member's heaviest.
            val grown = mapOf(a to 50, b to 10, c to 10, e to 30, g to 10)
            var moved = emptyMap<Int, Incarnation>().after(first)

            val second = balancer.round(rebalance.every * 2, balance(grown, moved))
            second shouldBe mapOf(e to three[2].node)
            moved = moved.after(second)
            balancer.round(rebalance.every * 3, balance(grown, moved)).shouldBeEmpty()
        }

        test("a shard just moved counts at the load it moved with until its new owner reports it") {
            val (a, b, c, d) = ownedBy(three[0], 4)
            val balancer = Balancer()
            val _ = balancer.round(Duration.ZERO, Balance.None)
            val first = balancer.round(rebalance.every, balance(listOf(a, b, c, d).associateWith { 10 }, emptyMap()))
            first shouldBe mapOf(a to three[1].node, b to three[2].node)

            val moved = emptyMap<Int, Incarnation>().after(first)
            balancer.round(rebalance.every * 2, balance(mapOf(c to 10, d to 10), moved)).shouldBeEmpty()
        }

        test(
            "with one hot range of ids on one node of three, its entities spread out, and no shard moves twice at once",
        ) {
            val every = 1.seconds
            val ports = List(3) { freePort() }
            val seeds = seedsAt(ports)
            val byLoad = Rebalance.byLoad(every = every, tolerance = 0.2, mostMoves = 4)
            val nodes = ports.indices.map { LoadNode("b${it + 1}", ports[it], seeds, byLoad, 1.minutes) }
            val pacer = Executors.newSingleThreadScheduledExecutor()
            val senders = Executors.newVirtualThreadPerTaskExecutor()
            try {
                nodes.forAll { n ->
                    n.cluster.await(30.seconds) { v -> v.members.count { it.status == Status.Up } == 3 } shouldBe true
                }
                val lead = nodes.first().cluster.view.leader
                nodes.forAll { n -> n.cluster.await(20.seconds) { v -> v.leader == lead } shouldBe true }
                val leader = nodes.first { it.cluster.self == lead }
                val history = CopyOnWriteArrayList<Pair<TimeSource.Monotonic.ValueTimeMark, Map<Int, Incarnation>>>()
                leader.cluster.onView { history += TimeSource.Monotonic.markNow() to leader.moved() }

                val hot = nodes.last()
                val members = hot.cluster.view.members
                val shards = (0 until Sharding.SHARDS)
                    .filter { Placement.owner("tally", it, members) == hot.cluster.self }.take(12).toSet()
                val ids = (0 until 100_000).map { "h-$it" }.filter { Placement.shardOf(it, Sharding.SHARDS) in shards }
                    .groupBy { Placement.shardOf(it, Sharding.SHARDS) }.values.map { it.take(10) }
                ids.flatten() shouldHaveSize 120
                // Every entity is asked once per tick, however long any one ask takes, so messages follow the entities.
                val round = AtomicInteger()
                val _ = pacer.scheduleAtFixedRate({
                    val next = round.getAndIncrement()
                    ids.flatten().forEachIndexed { i, id -> senders.execute { nodes[(next + i) % 3].tryTally(id) } }
                }, 0, 100, TimeUnit.MILLISECONDS)

                val evenAt = AtomicReference<TimeSource.Monotonic.ValueTimeMark>()
                val even = leader.cluster.await(30.seconds) { _ ->
                    val running = leader.running("tally").values
                    (running.sum() == 120 && running.max() <= 120 / 3 * 1.2).also {
                        if (it) evenAt.set(TimeSource.Monotonic.markNow())
                    }
                }
                val story = {
                    val t0 = history.first().first
                    history.map { (at, m) ->
                        "${(at - t0).inWholeMilliseconds}ms " + m.mapValues { it.value.node.name }
                    } +
                        listOf("even at ${evenAt.get()?.let { it - t0 }}", "hot ${hot.name} ${shards.sorted()}")
                }
                withClue({ "entities by member: ${leader.running("tally")} ${story()}" }) {
                    even shouldBe true
                }
                val _ = pacer.shutdownNow()

                val rounds = history.filter { it.first <= evenAt.get() }.zipWithNext().count { (was, now) ->
                    was.second != now.second
                }
                withClue(
                    { "the leader moved shards in at most three rounds before they were even ${story()}" },
                ) {
                    rounds shouldBeLessThanOrEqual 3
                }
                shards.forAll { shard ->
                    val times = history.zipWithNext().filter { (was, now) -> was.second[shard] != now.second[shard] }
                        .map { it.second.first }
                    times.zipWithNext().forAll { (was, now) ->
                        withClue("shard $shard moved twice within ${now - was}") {
                            (now - was >= every * 3 - 100.milliseconds) shouldBe true
                        }
                    }
                }
                withClue("no entity ran on two nodes at once") {
                    ranTwice.filter { it.startsWith("h-") }.shouldBeEmpty()
                }
            } finally {
                val _ = pacer.shutdownNow()
                val _ = senders.shutdownNow()
                nodes.forEach(LoadNode::close)
            }
        }
    }
}
