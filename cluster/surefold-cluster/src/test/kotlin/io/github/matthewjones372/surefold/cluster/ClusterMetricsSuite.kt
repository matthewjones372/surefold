package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.node
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.metrics
import dev.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun anyPort(): Int = ServerSocket(0).use { it.localPort }

/** A node on a thread of its own, measuring into [measured], that goes as a crashed node does when closed. */
private class MeasuredNode(val name: String, port: Int, seeds: Discovery) : AutoCloseable {
    val measured = NodeMetrics()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val joined = AtomicReference<Cluster>()
    private val thread = Thread.ofPlatform().start {
        metrics.locally(measured) {
            scope {
                val downing = Downing.keepMajority(2.seconds)
                joined.set(cluster(node(name, port), seeds, brisk, downing, leaveWithin = Duration.ZERO))
                ready.countDown()
                done.await()
            }
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return joined.get()
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

/** How many of [nodes] gauge themselves the leader. */
private fun leaders(nodes: List<MeasuredNode>) =
    nodes.sumOf { n -> n.measured.gauge("surefold.cluster.leader", "node" to n.name)!! }

object ClusterMetricsSuite : Suite() {
    init {
        test("members are gauged with one leader, and a crash is unreachable and not ready until it is downed") {
            val ports = List(3) { anyPort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val nodes = ports.mapIndexed { i, port -> MeasuredNode("m${i + 1}", port, seeds) }
            try {
                nodes.forAll { n ->
                    n.cluster.await(1.minutes) { v ->
                        v.members.count { it.status == Status.Up } == 3 && v.leader != null && v.unreachable.isEmpty()
                    } shouldBe true
                }

                nodes.forAll { n ->
                    n.measured.gauge("surefold.cluster.members", "node" to n.name, "status" to "Up") shouldBe 3.0
                }
                leaders(nodes) shouldBe 1.0
                nodes.forAll { n -> n.cluster.ready() shouldBe true }

                val (first, second, crashed) = nodes
                crashed.close()
                listOf(first, second).forAll { n ->
                    val seen = n.cluster.await(1.minutes) { v -> v.unreachable.any { it.name == crashed.name } }
                    seen shouldBe true
                    n.cluster.ready() shouldBe false
                    n.measured.gauge("surefold.cluster.unreachable", "node" to n.name) shouldBe 1.0
                }
                listOf(first, second).forAll { n ->
                    val gone = n.cluster.await(1.minutes) { v -> v.members.none { it.node.name == crashed.name } }
                    gone shouldBe true
                    n.cluster.ready() shouldBe true
                    n.measured.gauge("surefold.cluster.unreachable", "node" to n.name) shouldBe 0.0
                    n.measured.counter("surefold.cluster.downed", "node" to n.name) shouldBe 1.0
                }
                leaders(listOf(first, second)) shouldBe 1.0
            } finally {
                nodes.forEach(MeasuredNode::close)
            }
        }
    }
}
