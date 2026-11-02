package dev.surefold.cluster

import dev.surefold.actor.Address
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
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.scope
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/** Asks which node answers for an entity. */
private data class Holder(val reply: Reply<String>)

private val holderCodec = object : MessageCodec<Holder> {
    override fun write(message: Holder, out: WireOut) = out.reply(message.reply, Codecs.string)

    override fun read(input: WireIn): Holder = Holder(input.reply(Codecs.string))
}

private val quickly =
    Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 500.milliseconds)

private fun freePort(): Int = ServerSocket(0).use { it.localPort }

/** Spec 0103: a region moves a shard only for its own cluster's members. */
object MembersOnlySuite : Suite() {
    init {
        test("a Release sent from a node outside the cluster is a dead letter, and the shard stays where it was") {
            val port = freePort()
            val letters = LinkedBlockingQueue<DeadLetter>()
            val (before, after) = scope {
                onDeadLetter(letters::add)
                val self = node("ledger", port)
                val member = cluster(self, Discovery.static(Node("", "127.0.0.1", port)), quickly)
                val answering = behaviour<Holder, Unit>(Unit) { _, _, asked -> stay().also { asked.reply("ledger") } }
                val ledgers = member.sharding("ledger", holderCodec, 1.minutes) { answering }
                val before = ledgers.entity("l-1").ask(1.minutes) { Holder(it) }

                val shard = Placement.shardOf("l-1", Sharding.SHARDS)
                scope {
                    val outsider = node("outsider", freePort())
                    val region = outsider.remote(
                        Address(self.self.toString(), Sharding.path("ledger"), 0),
                        RegionCodec(holderCodec),
                    )
                    region.tell(Region.Release(shard, outsider.self))
                    val refused = generateSequence { letters.poll(1, TimeUnit.MINUTES) }
                        .first { it.message is Region.Release<*> }
                    refused.why shouldBe DeadLetter.Why.Unhandled
                    (refused.message as Region.Release<*>).sender shouldBe outsider.self
                }

                before to ledgers.entity("l-1").ask(1.minutes) { Holder(it) }
            }

            before shouldBeRight "ledger"
            after shouldBeRight "ledger"
        }
    }
}
