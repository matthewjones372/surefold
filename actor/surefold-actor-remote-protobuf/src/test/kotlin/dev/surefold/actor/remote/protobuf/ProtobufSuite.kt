package dev.surefold.actor.remote.protobuf

import com.google.protobuf.BoolValue
import com.google.protobuf.Int64Value
import com.google.protobuf.Message
import com.google.protobuf.StringValue
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
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeRight
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import java.util.concurrent.CountDownLatch
import kotlin.time.Duration.Companion.minutes

private fun text(value: String): StringValue = StringValue.of(value)

/** A request for a price, and the surefold reply its answer goes to: what `asked` writes. */
private data class Quote(val request: StringValue, val reply: Reply<Long>)

private val quote = Protobuf.asked(StringValue.parser(), Codecs.long, ::Quote, Quote::request, Quote::reply)

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

private fun <M : Any> MessageCodec<M>.roundTrip(message: M, refs: Refs = Kept()): M =
    decode(encode(message, refs), refs)

object ProtobufSuite : Suite() {
    private val protocol = Protobuf.oneOf {
        message(1, StringValue.parser())
        message(2, Int64Value.parser())
    }

    init {
        test("a generated message crosses as its own bytes and reads back equal") {
            Protobuf.codec(StringValue.parser()).roundTrip(text("order-42")) shouldBe text("order-42")
        }

        test("each message of a protocol reads back as the type it was written as") {
            listOf<Message>(text("placed"), Int64Value.of(7)).map { protocol.roundTrip(it) } shouldBe
                listOf(text("placed"), Int64Value.of(7))
        }

        test("a protocol with a tag or a type given twice fails when it is built") {
            shouldThrow<IllegalArgumentException>({
                Protobuf.oneOf {
                    message(1, StringValue.parser())
                    message(1, Int64Value.parser())
                }
            }) { e -> e.message shouldContain "tag 1" }
            shouldThrow<IllegalArgumentException>({
                Protobuf.oneOf {
                    message(1, StringValue.parser())
                    message(2, StringValue.parser())
                }
            }) { e -> e.message shouldContain "StringValue" }
        }

        test("a message the protocol has no tag for is refused, and a tag it does not know is not read") {
            shouldThrow<IllegalArgumentException> { protocol.roundTrip(BoolValue.of(true)) }
            val unknown = Protobuf.oneOf { message(9, StringValue.parser()) }.encode(text("x"), Kept())

            shouldThrow<WireException>({ protocol.decode(unknown, Kept()) }) { e ->
                e.message shouldContain "tag 9"
            }
        }

        test("bytes that are not the message are a wire exception, not Protobuf's own") {
            val notAString = Codecs.bytes.encode(byteArrayOf(0x0a, 0x7f), Kept())

            shouldThrow<WireException> { Protobuf.codec(StringValue.parser()).decode(notAString, Kept()) }
        }

        test("an ask whose request is a generated message is answered from another node") {
            val port = freePort()
            val ready = CountDownLatch(1)
            val done = CountDownLatch(1)
            val prices = Thread.ofPlatform().start {
                scope {
                    // Prices an item at a hundred a letter.
                    val pricing = behaviour<Quote, Unit>(Unit) { _, _, asked ->
                        stay().also { asked.reply(asked.request.value.length * 100L) }
                    }
                    val desk = spawn("prices", pricing)
                    node("prices", port).expose(desk, quote)
                    ready.countDown()
                    done.await()
                }
            }
            ready.await()
            try {
                val answer = scope {
                    val desk = node("shop", freePort())
                        .remote(Address("prices@127.0.0.1:$port", "/user/prices", 0), quote)
                    desk.ask(1.minutes) { Quote(text("apples"), it) }
                }

                answer shouldBeRight 600L
            } finally {
                done.countDown()
                prices.join()
            }
        }
    }
}
