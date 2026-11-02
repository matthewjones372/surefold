@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package io.github.matthewjones372.surefold.actor.remote.kotlinx

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Address
import io.github.matthewjones372.surefold.actor.InMemoryJournal
import io.github.matthewjones372.surefold.actor.InMemorySnapshots
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Reply
import io.github.matthewjones372.surefold.actor.ask
import io.github.matthewjones372.surefold.actor.behaviour
import io.github.matthewjones372.surefold.actor.every
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.remote.Codecs
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import io.github.matthewjones372.surefold.actor.remote.Refs
import io.github.matthewjones372.surefold.actor.remote.WireException
import io.github.matthewjones372.surefold.actor.remote.decode
import io.github.matthewjones372.surefold.actor.remote.encode
import io.github.matthewjones372.surefold.actor.remote.node
import io.github.matthewjones372.surefold.actor.spawn
import io.github.matthewjones372.surefold.actor.stay
import io.github.matthewjones372.surefold.actor.testActors
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.serializer
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** A request for a price, and the surefold reply its answer goes to: what `asked` writes. */
private data class Quote(val request: Till.Paid, val reply: Reply<Long>)

private val quote = Kotlinx.asked(serializer<Till.Paid>(), Codecs.long, ::Quote, Quote::request, Quote::reply)

/** Refs that keep every reply they are given, and hand the same one back for its address. */
private class Kept : Refs {
    private val replies = mutableMapOf<Address, Reply<*>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>) = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address =
        Address("here", "/temp/reply-${replies.size}", 0).also { replies[it] = reply }

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = error("no refs here")

    @Suppress("UNCHECKED_CAST")
    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>) = replies.getValue(address) as Reply<A>
}

private fun <M : Any> MessageCodec<M>.roundTrip(message: M): M =
    Kept().let { refs -> decode(encode(message, refs), refs) }

private val everyKind = listOf(
    Till.Paid(Pence(250), "order-42"),
    Till.Refunded(Pence(100), Reason.Unwanted),
    Till.Closed,
)

object KotlinxSuite : Suite() {
    init {
        test("one data class crosses as its own bytes and reads back equal") {
            val takings = Takings(Pence(350), listOf("a", "b"), open = true)
            Kotlinx.codec(serializer<Takings>()).roundTrip(takings) shouldBe takings
            Kotlinx.state(serializer<Takings>()).let { it.decode(it.encode(takings)) } shouldBe takings
        }

        test(
            "each class of a table reads back as the class it was written as, between nodes and in the journal",
        ) {
            everyKind.map { tills.messages().roundTrip(it) } shouldBe everyKind
            everyKind.map { tills.events().let { codec -> codec.decode(codec.encode(it)) } } shouldBe everyKind
        }

        test("a table writes in any binary format it is given") {
            val cbor = Kotlinx.oneOf<Till>(Cbor) {
                message<Till.Paid>(1)
                message<Till.Refunded>(2)
            }
            val paid = Till.Paid(Pence(1), "cbor")
            cbor.events().let { it.decode(it.encode(paid)) } shouldBe paid
        }

        test("a table with a tag or a class given twice fails when it is built") {
            shouldThrow<IllegalArgumentException>({
                Kotlinx.oneOf<Till> {
                    message<Till.Paid>(1)
                    message<Till.Refunded>(1)
                }
            }) { e -> e.message shouldContain "tag 1" }
            shouldThrow<IllegalArgumentException>({
                Kotlinx.oneOf<Till> {
                    message<Till.Paid>(1)
                    message<Till.Paid>(2)
                }
            }) { e -> e.message shouldContain "Paid" }
        }

        test("a class the table has no tag for is refused, and a tag it does not know is not read") {
            val short = Kotlinx.oneOf<Till> { message<Till.Paid>(1) }
            shouldThrow<IllegalArgumentException>({ short.write(Till.Closed) }) { e ->
                e.message shouldContain
                    "Closed"
            }

            val closed = tills.write(Till.Closed)
            shouldThrow<SerializationException>({ short.read(closed) }) { e -> e.message shouldContain "tag 3" }
            shouldThrow<WireException> {
                short.messages().decode(tills.messages().encode(Till.Closed, Kept()), Kept())
            }
        }

        test("an entity's events and snapshot are written through a table and survive a replay") {
            val journal = InMemoryJournal()
            val snapshots = InMemorySnapshots()
            val id = PersistenceId("till", "t-1")
            val till = persistent<Till, Till, Takings>(
                id = id,
                empty = Takings(Pence(0), emptyList(), open = true),
                codec = tills.events(),
                snapshots = every(2, Kotlinx.state(serializer<Takings>())),
                command = { _, _, happened -> persist(happened) },
                event = { takings, happened ->
                    when (happened) {
                        is Till.Paid -> takings.copy(
                            total = Pence(takings.total.count + happened.amount.count),
                            references = takings.references + happened.reference,
                        )

                        is Till.Refunded -> takings.copy(total = Pence(takings.total.count - happened.amount.count))

                        Till.Closed -> takings.copy(open = false)
                    }
                },
            )
            val run = {
                testActors(journal = journal, snapshots = snapshots) {
                    val ref = spawn("till", till)
                    everyKind.forEach(ref::send)
                    ref.state.value
                }
            }
            val first = run()
            first shouldBe Takings(Pence(150), listOf("order-42"), open = false)
            journal.read(id).size shouldBe 3
            // A second life replays what the first wrote, from its snapshot on, before taking three more.
            run() shouldBe Takings(Pence(300), listOf("order-42", "order-42"), open = false)
        }

        test("an ask whose request is a data class is answered from another node") {
            val port = ServerSocket(0).use(ServerSocket::getLocalPort)
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val tillNode = Thread.ofPlatform().start {
                scope {
                    // Answers with twice what was paid.
                    val doubling = behaviour<Quote, Unit>(Unit) { _, _, asked ->
                        stay().also { asked.reply(asked.request.amount.count * 2) }
                    }
                    node("till", port).expose(spawn("till", doubling), quote)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val answer = scope {
                    val till = node("shop", ServerSocket(0).use(ServerSocket::getLocalPort))
                        .remote(Address("till@127.0.0.1:$port", "/user/till", 0), quote)
                    till.ask(1.minutes) { Quote(Till.Paid(Pence(21), "q"), it) }
                }
                answer shouldBeRight 42L
            } finally {
                done.countDown()
                tillNode.join()
            }
        }
    }
}
