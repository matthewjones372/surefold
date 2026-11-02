package dev.surefold.actor.remote.avro

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Reply
import dev.surefold.actor.ask
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Refs
import dev.surefold.actor.remote.WireException
import dev.surefold.actor.remote.decode
import dev.surefold.actor.remote.encode
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import org.apache.avro.generic.GenericData
import org.apache.avro.generic.GenericRecord
import org.apache.avro.message.BinaryMessageEncoder
import org.apache.avro.message.SchemaStore
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

/** A placed order to price, and the surefold reply its price goes to: what `asked` writes. */
private data class Price(val placed: Placed, val reply: Reply<Long>)

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

object AvroSuite : Suite() {
    // Filled here and only read after, so the tests running at once share it safely.
    private val schemas = SchemaStore.Cache().apply { addSchema(Placed.VERSION_2) }
    private val placed = Avro.codec(Placed::class.java, schemas)
    private val price = Avro.asked(Placed::class.java, schemas, Codecs.long, ::Price, Price::placed, Price::reply)

    init {
        test("a record crosses in single-object encoding and reads back equal") {
            placed.decode(placed.encode(Placed("o-42", 250), Kept()), Kept()) shouldBe Placed("o-42", 250)
        }

        test("a record a node wrote with a newer schema is read by one on the older, through the store") {
            val newer = GenericData.Record(Placed.VERSION_2).apply {
                put("order", "o-7")
                put("pence", 120L)
                put("currency", "EUR")
            }
            val written = BinaryMessageEncoder<GenericRecord>(GenericData.get(), Placed.VERSION_2).encode(newer)
            val frame = Codecs.bytes.encode(ByteArray(written.remaining()).also(written::get), Kept())

            placed.decode(frame, Kept()) shouldBe Placed("o-7", 120)
        }

        test("a record whose schema no store has, or bytes that are not a record, is a wire exception") {
            val unknown = Avro.codec(Placed::class.java, SchemaStore.Cache())
            val newer = GenericData.Record(Placed.VERSION_2).apply {
                put("order", "o-7")
                put("pence", 120L)
                put("currency", "EUR")
            }
            val written = BinaryMessageEncoder<GenericRecord>(GenericData.get(), Placed.VERSION_2).encode(newer)

            shouldThrow<WireException> {
                unknown.decode(Codecs.bytes.encode(ByteArray(written.remaining()).also(written::get), Kept()), Kept())
            }
            shouldThrow<WireException> { placed.decode(Codecs.bytes.encode(byteArrayOf(1, 2, 3), Kept()), Kept()) }
        }

        test("an ask whose request is a record is answered from another node") {
            val port = ServerSocket(0).use(ServerSocket::getLocalPort)
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val pricing = Thread.ofPlatform().start {
                scope {
                    // Adds a fifth for the tax.
                    val taxed = behaviour<Price, Unit>(Unit) { _, _, asked ->
                        stay().also { asked.reply(asked.placed.pence * 6 / 5) }
                    }
                    node("pricing", port).expose(spawn("pricing", taxed), price)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val answer = scope {
                    val desk = node("shop", ServerSocket(0).use(ServerSocket::getLocalPort))
                        .remote(Address("pricing@127.0.0.1:$port", "/user/pricing", 0), price)
                    desk.ask(1.minutes) { Price(Placed("o-1", 500), it) }
                }

                answer shouldBeRight 600L
            } finally {
                done.countDown()
                pricing.join()
            }
        }
    }
}
