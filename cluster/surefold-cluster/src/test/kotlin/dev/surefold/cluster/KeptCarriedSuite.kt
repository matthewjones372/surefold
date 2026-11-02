package dev.surefold.cluster

import dev.surefold.actor.Address
import dev.surefold.actor.Reply
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Answers with the request id on a line the handler writes. */
private data class WhoAsked(val reply: Reply<String>)

private val whoAskedCodec = object : MessageCodec<WhoAsked> {
    override fun write(message: WhoAsked, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn) = WhoAsked(input.reply(Codecs.string))
}

private fun answering() = behaviour<WhoAsked, Unit>(Unit) { _, _, message ->
    val heard = capturingLogs { logs ->
        logInfo("asked")
        logs.all().single().annotations["request_id"].orEmpty()
    }
    stay().also { message.reply(heard) }
}

private val brisk =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/** A node on a thread of its own whose kind "ledger" runs only on members with the role "ledger". */
private class LedgerNode(name: String, port: Int, seeds: Discovery, roles: Set<String>) : AutoCloseable {
    private val done = CountDownLatch(1)
    private val ready = CountDownLatch(1)
    private val opened = AtomicReference<Pair<Cluster, Sharded<WhoAsked>>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            val cluster = cluster(node(name, port), seeds, brisk, roles = roles)
            opened.set(cluster to cluster.sharding("ledger", whoAskedCodec, 1.minutes, role = "ledger") { answering() })
            ready.countDown()
            done.await()
        }
    }

    private fun opened(): Pair<Cluster, Sharded<WhoAsked>> {
        ready.await()
        return opened.get()
    }

    val cluster: Cluster get() = opened().first

    val ledgers: Sharded<WhoAsked> get() = opened().second

    override fun close() {
        done.countDown()
        thread.join()
    }
}

/** Spec 0122: a message a region keeps for a shard with no owner yet is handled in its sender's context. */
object KeptCarriedSuite : Suite() {
    init {
        test("a message kept until a member with the role joins is handled with what its sender had bound") {
            val ports = List(2) { ServerSocket(0).use { socket -> socket.localPort } }.sorted()
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            val nodes = mutableListOf(LedgerNode("front", ports[0], seeds, emptySet()))
            try {
                val front = nodes.single()
                front.cluster.await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 1 } shouldBe
                    true
                val answered = CompletableFuture<String>()
                val reply = object : Reply<String> {
                    override val address = Address("test", "/reply", 0)

                    override fun invoke(answer: String) {
                        answered.complete(answer)
                    }
                }
                // No member has the role, so the front's region keeps this until one joins.
                logAnnotated("request_id" to "r-3") { front.ledgers.entity("l-1").tell(WhoAsked(reply)) }
                nodes += LedgerNode("host", ports[1], seeds, setOf("ledger"))

                answered.get(1, TimeUnit.MINUTES) shouldBe "r-3"
            } finally {
                nodes.forEach(LedgerNode::close)
            }
        }
    }
}
