package dev.surefold.cluster

import dev.surefold.actor.Reply
import dev.surefold.actor.Signal
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.onSignal
import dev.surefold.actor.onStart
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Asks which node the entity it reaches runs on. */
internal data class Which(val reply: Reply<String>)

internal val whichCodec = object : MessageCodec<Which> {
    override fun write(message: Which, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): Which = Which(input.reply(Codecs.string))
}

/** Probes calm enough that three nodes busy with entities never miss each other. */
internal val steady =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

internal fun loadPort(): Int = ServerSocket(0).use { it.localPort }

/** How many of each entity run now, across every node in this JVM; and which ever ran on two at once. */
internal val runningNow = ConcurrentHashMap<String, AtomicInteger>()
internal val ranTwice: MutableSet<String> = ConcurrentHashMap.newKeySet()

internal fun replyingWith(name: String, id: String) =
    behaviour<Which, Unit>(Unit) { _, _, which -> stay().also { which.reply(name) } }
        .onStart { if (runningNow.computeIfAbsent(id) { AtomicInteger() }.incrementAndGet() > 1) ranTwice += id }
        .onSignal { _, _, signal ->
            if (signal == Signal.Stopping) runningNow.getValue(id).decrementAndGet()
            stay()
        }

internal fun seedsAt(ports: List<Int>) = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())

/**
 * A node on a thread of its own with two kinds whose entities answer its name: "tally", which rebalances as
 * [rebalance] says, and "plain", which does not.
 */
internal class LoadNode(
    val name: String,
    port: Int,
    seeds: Discovery,
    rebalance: Rebalance,
    passivateAfter: Duration,
) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Triple<Cluster, Sharded<Which>, Sharded<Which>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port), seeds, steady)
            val answer = { id: String -> replyingWith(name, id) }
            val tally = cluster.sharding("tally", whichCodec, passivateAfter, rebalance = rebalance, entity = answer)
            val plain = cluster.sharding("plain", whichCodec, passivateAfter, entity = answer)
            opened.set(Triple(cluster, tally, plain))
            ready.countDown()
            done.await()
        }
    }

    private fun opened(): Triple<Cluster, Sharded<Which>, Sharded<Which>> {
        ready.await()
        return opened.get()
    }

    val cluster: Cluster get() = opened().first

    fun tally(id: String): String = opened().second.entity(id).ask(1.minutes) { Which(it) }.getOrNull()!!

    /** Asks [id] of "tally" where it runs, or null if the ask is lost, as one can be while its shard moves. */
    fun tryTally(id: String): String? = opened().second.entity(id).ask(5.seconds) { Which(it) }.getOrNull()

    fun plain(id: String): String = opened().third.entity(id).ask(1.minutes) { Which(it) }.getOrNull()!!

    /** The entities of [kind] each member says it runs, by the member's name, as this node's gossip has it now. */
    fun running(kind: String): Map<String, Int> = cluster.view.members.associate { member ->
        member.node.name to (cluster.balance.loads[member.node]?.get(kind)?.values?.sumOf { it.entities } ?: 0)
    }

    /** The shards of "tally" the leader has moved, as this node's gossip has it now. */
    fun moved() = cluster.balance.moved["tally"].orEmpty()

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object LoadSuite : Suite() {
    init {
        test(
            "every member sees each member's running entities of a kind that rebalances, as they start and passivate",
        ) {
            val ports = List(3) { loadPort() }
            val seeds = seedsAt(ports)
            // So wide a tolerance that nothing moves: this is about the load, not what the leader does with it.
            val every = Rebalance.byLoad(every = 200.milliseconds, tolerance = 100.0)
            val nodes =
                ports.indices.map { LoadNode("l${it + 1}", ports[it], seeds, every, passivateAfter = 6.seconds) }
            try {
                nodes.forAll { n ->
                    n.cluster.await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 3 } shouldBe true
                }

                val placed = (0 until 90).map { nodes[it % 3].tally("t-$it") }
                (0 until 30).forEach { nodes[it % 3].plain("p-$it") }
                val expected = nodes.associate { n -> n.name to placed.count { it == n.name } }
                nodes.forAll { n ->
                    withClue({ "${n.name} sees ${n.running("tally")}, not $expected" }) {
                        n.cluster.await(20.seconds) { _ -> n.running("tally") == expected } shouldBe true
                    }
                    withClue("a kind that does not rebalance carries no load") {
                        n.cluster.balance.loads.values.none { "plain" in it } shouldBe true
                    }
                }

                val none = nodes.associate { it.name to 0 }
                nodes.forAll { n ->
                    withClue({ "${n.name} sees ${n.running("tally")} once they passivate" }) {
                        n.cluster.await(20.seconds) { _ -> n.running("tally") == none } shouldBe true
                    }
                }
            } finally {
                nodes.forEach(LoadNode::close)
            }
        }
    }
}
