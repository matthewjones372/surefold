package dev.surefold.cluster

import dev.surefold.actor.remote.freePort
import dev.surefold.actor.DeadLetter
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.onDeadLetter
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
import dev.surefold.scope
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Asks which node answers. */
private data class WhoAnswers(val reply: Reply<String>)

private val whoCodec = object : MessageCodec<WhoAnswers> {
    override fun write(message: WhoAnswers, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): WhoAnswers = WhoAnswers(input.reply(Codecs.string))
}

private val prompt =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 1_000.milliseconds)

/**
 * A node that joins at once, and opens its region of "ledger" only once [open] is counted down, keeping every dead
 * letter meanwhile. Only members with the role "ledger" host the kind.
 */
private class LateNode(val name: String, port: Int, seeds: Discovery, roles: Set<String>, val open: CountDownLatch) :
    AutoCloseable {
    val letters = LinkedBlockingQueue<DeadLetter>()
    private val done = CountDownLatch(1)
    private val joined = CountDownLatch(1)
    private val opened = CountDownLatch(1)
    private val member = AtomicReference<Cluster>()
    private val ledgers = AtomicReference<Sharded<WhoAnswers>>()
    private val thread = Thread.ofPlatform().start {
        scope {
            onDeadLetter(letters::add)
            member.set(cluster(node(name, port), seeds, prompt, roles = roles))
            joined.countDown()
            open.await()
            val answering = behaviour<WhoAnswers, Unit>(Unit) { _, _, asked -> stay().also { asked.reply(name) } }
            ledgers.set(member.get().sharding("ledger", whoCodec, 1.minutes, role = "ledger") { answering })
            opened.countDown()
            done.await()
        }
    }

    fun cluster(): Cluster {
        joined.await()
        return member.get()
    }

    fun who(id: String): String? {
        opened.await()
        return ledgers.get().entity(id).ask(1.minutes) { WhoAnswers(it) }.getOrNull()
    }

    override fun close() {
        open.countDown()
        done.countDown()
        thread.join()
    }
}

object LateRegionSuite : Suite() {
    init {
        test("a member that opens its region after a shard was won still lets the winner run it") {
            val ports = List(2) { freePort() }
            val seeds = Discovery.static(*ports.map { Node("", "127.0.0.1", it) }.toTypedArray())
            // The ledger opens its region only once both are Up, so it wins its shards with the web node there to ask:
            // opened sooner, it wins them alone and asks nobody.
            val now = CountDownLatch(1)
            val later = CountDownLatch(1)
            val ledger = LateNode("ledger", ports[0], seeds, setOf("ledger"), now)
            val web = LateNode("web", ports[1], seeds, setOf("web"), later)
            try {
                listOf(ledger, web).forAll { n ->
                    n.cluster().await(1.minutes) { v -> v.members.count { it.status == Status.Up } == 2 } shouldBe true
                }
                now.countDown()
                // The winner has asked the web node to release its shards, before there was a region there to hear it.
                val region = Sharding.path("ledger")
                val _ = generateSequence { web.letters.poll(1, TimeUnit.MINUTES) }.first { it.recipient.path == region }

                val answered = CompletableFuture.supplyAsync { ledger.who("l-1") }
                later.countDown()

                answered.get(1, TimeUnit.MINUTES) shouldBe "ledger"
                web.who("l-2") shouldBe "ledger"
            } finally {
                listOf(web, ledger).forEach(LateNode::close)
            }
        }
    }
}
