package dev.surefold.cluster

import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

private val unhurried =
    Gossiping(probeEvery = 500.milliseconds, ackWithin = 250.milliseconds, formAfter = 1_000.milliseconds)

/** A node on a thread of its own with the kind "till", whose entities run only on members with the role "till". */
private class TillNode(name: String, port: Int, seeds: Discovery, roles: Set<String>, open: CountDownLatch) :
    AutoCloseable {
    val heard = LinkedBlockingQueue<Int>()
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<Int>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port), seeds, unhurried, roles = roles, leaveWithin = Duration.ZERO)
            // Each entity's first message holds its step until the burst is all sent, so its mailbox fills behind it.
            val tills = cluster.sharding("till", Codecs.int, 10.minutes, role = "till") { _ ->
                behaviour<Int, Unit>(Unit) { _, _, n -> stay().also { if (n == 0) open.await() else heard += n } }
            }
            opened.set(cluster to tills)
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val tills: Sharded<Int>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object BusyEntitySuite : Suite() {
    init {
        test("a burst of 5,000 from another node to a busy entity is applied in order, and no region stops") {
            val ports = List(3) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val open = CountDownLatch(1)
            val nodes = listOf(
                TillNode("t1", ports[0], seeds, setOf("till"), open),
                TillNode("t2", ports[1], seeds, emptySet(), open),
                TillNode("t3", ports[2], seeds, emptySet(), open),
            )
            try {
                nodes.forAll { n ->
                    n.cluster.await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 3 } shouldBe true
                }
                val (host, sender) = nodes
                val till = sender.tills.entity("till-1")
                till.tell(0)
                (1..5_000).forEach(till::tell)
                open.countDown()

                List(5_000) { host.heard.poll(1, TimeUnit.MINUTES) } shouldContainExactly (1..5_000).toList()
                nodes.last().tills.entity("till-2").tell(7)
                host.heard.poll(1, TimeUnit.MINUTES) shouldBe 7
            } finally {
                open.countDown()
                nodes.forEach(TillNode::close)
            }
        }
    }
}
