package dev.surefold.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Asks where the actor it reaches runs. */
private data class WhereAreYou(val reply: Reply<String>)

private val whereCodec = object : MessageCodec<WhereAreYou> {
    override fun write(message: WhereAreYou, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): WhereAreYou = WhereAreYou(input.reply(Codecs.string))
}

private fun answering(on: String) = behaviour<WhereAreYou, Unit>(Unit) { _, _, asked ->
    stay().also {
        asked.reply(on)
    }
}

private val lively =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** A node with the ledgers sharded and the clock singleton placed on members with the role "ledger". */
private class RoleNode(val name: String, port: Int, seeds: Discovery, roles: Set<String>) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Triple<Cluster, Sharded<WhereAreYou>, ActorRef<WhereAreYou>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port), seeds, lively, roles = roles)
            val ledgers = cluster.sharding("ledger", whereCodec, 1.minutes, role = "ledger") { answering(name) }
            val clock = cluster.singleton("clock", whereCodec, role = "ledger") { answering(name) }
            opened.set(Triple(cluster, ledgers, clock))
            ready.countDown()
            done.await()
        }
    }

    private fun opened(): Triple<Cluster, Sharded<WhereAreYou>, ActorRef<WhereAreYou>> {
        ready.await()
        return opened.get()
    }

    val cluster: Cluster get() = opened().first

    fun where(id: String): String = opened().second.entity(id).ask(1.minutes) { WhereAreYou(it) }.getOrNull()!!

    fun clock(): String = opened().third.ask(1.minutes) { WhereAreYou(it) }.getOrNull()!!

    override fun close() {
        done.countDown()
        thread.join()
    }
}

object RolePlacementSuite : Suite() {
    init {
        test(
            "entities and a singleton run only on members with the role, reached from all, and wait while none is up",
        ) {
            val ports = List(5) { freePort() }
            val seeds = Discovery.static(*ports.take(4).map { Node("", "127.0.0.1", it) }.toTypedArray())
            val ledger = setOf("ledger")
            val offices = mutableListOf(
                RoleNode("o1", ports[0], seeds, ledger),
                RoleNode("o2", ports[1], seeds, setOf("web")),
                RoleNode("o3", ports[2], seeds, ledger),
                RoleNode("o4", ports[3], seeds, setOf("web")),
            )
            try {
                offices.forAll { o ->
                    o.cluster.await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 4 } shouldBe true
                }

                val answers = (0 until 500).map { offices[it % 4].where("l-$it") }
                answers.toSet() shouldBe setOf("o1", "o3")
                val view = offices[1].cluster.view
                val older = view.members.filter { "ledger" in it.roles }.minBy { it.upNumber }.node.name
                offices.forAll { it.clock() shouldBe older }

                val (o1, o2, o3, o4) = offices
                listOf(o1, o3).forEach { it.close() }
                listOf(o2, o4).forAll { o ->
                    o.cluster.await(1.minutes) { v -> v.members.none { "ledger" in it.roles } } shouldBe true
                }
                val waiting = CompletableFuture.supplyAsync { o2.where("l-1") }
                offices += RoleNode("o5", ports[4], seeds, ledger)

                waiting.get(1, TimeUnit.MINUTES) shouldBe "o5"
                o4.clock() shouldBe "o5"
            } finally {
                offices.forEach(RoleNode::close)
            }
        }
    }
}
