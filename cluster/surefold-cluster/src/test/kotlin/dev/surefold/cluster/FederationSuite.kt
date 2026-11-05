package dev.surefold.cluster

import dev.surefold.actor.Address
import dev.surefold.actor.AskFailure
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
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTimedValue

/** Asks which cluster answers for an account. */
private data class WhoHolds(val reply: Reply<String>)

private val whoHoldsCodec =
    object : MessageCodec<WhoHolds> {
        override fun write(message: WhoHolds, out: WireOut) = out.reply(message.reply, Codecs.string)

        override fun read(input: WireIn): WhoHolds = WhoHolds(input.reply(Codecs.string))
    }

private val brisk = Gossiping(probeEvery = 200.milliseconds, ackWithin = 60.milliseconds, formAfter = 500.milliseconds)

private fun answering(name: String) =
    behaviour<WhoHolds, Unit>(Unit) { _, _, asked -> stay().also { asked.reply(name) } }

/**
 * A one-node cluster named [name] at [port], hosting "account", until closed: its entities answer with [name]. It
 * reaches the accounts of the cluster at [northPort] as "north".
 */
private class OneNodeCluster(name: String, port: Int, northPort: Int) : AutoCloseable {
    private val ready = CountDownLatch(1)
    private val done = CountDownLatch(1)
    private lateinit var north: FederatedKind<WhoHolds>
    private val thread =
        Thread.ofPlatform().start {
            scope {
                val member = cluster(node(name, port), Discovery.static(Node("", "127.0.0.1", port)), brisk)
                check(member.await(1.minutes) { v -> v.members.any { it.status == Status.Up } }) {
                    "$name never came up"
                }
                val _ = member.sharding("account", whoHoldsCodec, 1.minutes) { answering(name) }
                member.acceptFederated("north")
                north =
                    member
                        .federate("north", Discovery.static(Node("", "127.0.0.1", northPort)), name, plaintext = true)
                        .sharding("account", whoHoldsCodec)
                ready.countDown()
                done.await()
            }
        }

    /** Who answers for [id] in the north cluster, asked from this one. */
    fun askNorth(id: String) = north.entity(id).ask(1.minutes) { WhoHolds(it) }

    init {
        ready.await()
    }

    override fun close() {
        done.countDown()
        thread.join()
    }
}

/** Spec 0103: one cluster asks another's entities, through its regions, without joining it. */
object FederationSuite : Suite() {
    init {
        test("a cluster asks another's entities, fails at once while it is gone, and asks again once it is back") {
            val southPort = freePort()
            val northPort = freePort()
            val letters = LinkedBlockingQueue<DeadLetter>()
            var south = OneNodeCluster("south", southPort, northPort)
            try {
                scope {
                    onDeadLetter(letters::add)
                    val self = Discovery.static(Node("", "127.0.0.1", northPort))
                    val north = cluster(node("north", northPort), self, brisk)
                    check(north.await(1.minutes) { v -> v.members.any { it.status == Status.Up } }) { "north is down" }
                    val _ = north.sharding("account", whoHoldsCodec, 1.minutes) { answering("north") }
                    north.acceptFederated("south")
                    val accounts =
                        north
                            .federate(
                                "south",
                                Discovery.static(Node("", "127.0.0.1", southPort)),
                                from = "north",
                                retryAfter = 1.seconds,
                                plaintext = true,
                            )
                            .sharding("account", whoHoldsCodec)

                    accounts.entity("s-7").ask(1.minutes) { WhoHolds(it) } shouldBeRight "south"
                    south.askNorth("n-3") shouldBeRight "north"

                    south.close()
                    // A frame written before north sees the connection close is lost, not dropped: at most once. Told
                    // until one is dropped, so north knows south is gone before it asks.
                    do {
                        accounts.entity("s-9").tell(WhoHolds(DroppedReply))
                    } while (letters.poll(100, TimeUnit.MILLISECONDS)?.why != DeadLetter.Why.Unreachable)
                    val gone = measureTimedValue { accounts.entity("s-7").ask(1.minutes) { WhoHolds(it) } }
                    gone.value shouldBe AskFailure.Unreachable.left()
                    withClue("the ask answered after ${gone.duration}, of the minute it was given") {
                        (gone.duration < 20.seconds) shouldBe true
                    }
                    accounts.entity("s-8").tell(WhoHolds(DroppedReply))
                    val letter = generateSequence {
                        letters.poll(1, TimeUnit.MINUTES)
                    }
                        .first { it.why == DeadLetter.Why.Unreachable && it.recipient.path == Sharding.path("account") }
                    letter.recipient.node shouldBe "@127.0.0.1:$southPort"

                    south = OneNodeCluster("south", southPort, northPort)
                    val back = generateSequence {
                        accounts.entity("s-7").ask(2.seconds) { WhoHolds(it) }
                    }
                        .take(60)
                        .firstOrNull { it.isRight() }
                    withClue("an ask once south is back, without restarting north") { back shouldBe "south".right() }
                }
            } finally {
                south.close()
            }
        }

        test("a cluster not accepted is refused, as a dead letter, and is answered once it is accepted") {
            val southPort = freePort()
            val letters = LinkedBlockingQueue<DeadLetter>()
            scope {
                onDeadLetter(letters::add)
                val south = cluster(node("south", southPort), Discovery.static(Node("", "127.0.0.1", southPort)), brisk)
                check(south.await(1.minutes) { v -> v.members.any { it.status == Status.Up } }) { "south is down" }
                val _ = south.sharding("account", whoHoldsCodec, 1.minutes) { answering("south") }
                south.acceptFederated("east")
                val westPort = freePort()
                val west = cluster(node("west", westPort), Discovery.static(Node("", "127.0.0.1", westPort)), brisk)
                val accounts =
                    west
                        .federate(
                            "south",
                            Discovery.static(Node("", "127.0.0.1", southPort)),
                            from = "west",
                            plaintext = true,
                        )
                        .sharding("account", whoHoldsCodec)

                accounts.entity("s-1").ask(2.seconds) { WhoHolds(it) } shouldBe AskFailure.TimedOut.left()
                val refused = generateSequence {
                    letters.poll(1, TimeUnit.MINUTES)
                }
                    .first { it.why == DeadLetter.Why.Unhandled }
                refused.recipient.path shouldBe Sharding.path("account")
                (refused.message as Region.Envelope<*>).from shouldBe "west"

                south.acceptFederated("west")
                accounts.entity("s-1").ask(1.minutes) { WhoHolds(it) } shouldBeRight "south"
            }
        }

        test("federating without TLS needs plaintext said outright") {
            val port = freePort()
            scope {
                val alone = cluster(node("alone", port), Discovery.static(Node("", "127.0.0.1", port)), brisk)
                shouldThrow<IllegalArgumentException> {
                    alone.federate("other", Discovery.static(Node("", "127.0.0.1", port)), from = "alone")
                }
            }
        }
    }
}

/** A reply nobody waits on, for a tell that is meant to be a dead letter. */
private object DroppedReply : Reply<String> {
    override val address = Address("", "/dropped", 0)

    override fun invoke(answer: String) = Unit
}
