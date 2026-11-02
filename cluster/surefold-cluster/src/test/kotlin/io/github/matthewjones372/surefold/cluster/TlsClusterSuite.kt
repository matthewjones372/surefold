package io.github.matthewjones372.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.junit.withClue
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.remote.Codecs
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.TestCertificates
import io.github.matthewjones372.surefold.actor.remote.Tls
import io.github.matthewjones372.surefold.actor.remote.WireIn
import io.github.matthewjones372.surefold.actor.remote.WireOut
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.actor.stay
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/** Asks a desk which node it runs on. */
private data class Desk(val reply: Reply<String>)

private val deskCodec = object : MessageCodec<Desk> {
    override fun write(message: Desk, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): Desk = Desk(input.reply(Codecs.string))
}

private val lively =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

private fun portFree(): Int = ServerSocket(0).use { it.localPort }

/** A node of a cluster over [tls], with desks sharded on it, on a thread of its own until [close]. */
private class Office(val name: String, port: Int, seeds: Discovery, tls: Tls) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<Desk>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port, tls = tls), seeds, lively)
            val desks = cluster.sharding("desk", deskCodec, passivateAfter = 1.minutes) { _ ->
                behaviour<Desk, Unit>(Unit) { _, _, where -> stay().also { where.reply(name) } }
            }
            opened.set(cluster to desks)
            ready.countDown()
            done.await()
        }
    }

    val cluster: Cluster
        get() {
            ready.await()
            return opened.get().first
        }

    val desks: Sharded<Desk>
        get() {
            ready.await()
            return opened.get().second
        }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object TlsClusterSuite : Suite() {
    init {
        test("three nodes form a cluster and shard over TLS, and one another CA signed never joins") {
            val ports = List(4) { portFree() }
            val seeds = Discovery.static(*ports.take(3).map { Node("", "127.0.0.1", it) }.toTypedArray())
            val offices = (1..3).map { Office("n$it", ports[it - 1], seeds, TestCertificates.tls("n$it")) }
            val opened = offices.toMutableList()
            try {
                offices.forAll { office ->
                    val up = office.cluster.await(1.minutes) { view ->
                        view.members.count { it.status == Status.Up } == 3
                    }
                    withClue("${office.name} sees 3 Up") { up shouldBe true }
                }
                val shard = Placement.shardOf("d-7", Sharding.SHARDS)
                val owner = Placement.owner("desk", shard, offices.first().cluster.view.members)?.name

                offices
                    .map { it.desks.entity("d-7").ask<Desk, String>(1.minutes) { reply -> Desk(reply) } }
                    .forAll { answered -> answered.getOrNull() shouldBe owner }

                val stranger = Office("n4", ports[3], seeds, TestCertificates.tls("n4", ca = "other-ca")).also {
                    opened +=
                        it
                }
                // Long enough for a node the cluster trusted to have joined several times over, at this gossip's pace.
                val joined = stranger.cluster.await(3.seconds) { view -> view.members.size > 1 }
                withClue("n4, signed by another CA, joined") { joined shouldBe false }
                offices.forAll { office ->
                    office.cluster.view.members.map { it.node.name } shouldContainExactlyInAnyOrder
                        listOf("n1", "n2", "n3")
                }
            } finally {
                opened.forEach(Office::close)
            }
        }
    }
}
