package io.github.matthewjones372.surefold.actor.remote

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Address
import io.github.matthewjones372.surefold.actor.Confirmed
import io.github.matthewjones372.surefold.actor.Delivered
import io.github.matthewjones372.surefold.actor.Delivery
import io.github.matthewjones372.surefold.actor.Reply

/** A payment a durable producer can keep. */
private data class Deposit(val pence: Long, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

/** A payment sent only by a producer in memory: it never says how to redeliver itself. */
private data class Tip(val pence: Long, override val delivery: Delivery) : Delivered

/** A command that wants an answer, which no producer started again could give. */
private data class Quote(val reply: Reply<Long>, override val delivery: Delivery) : Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

private val depositCodec = object : MessageCodec<Deposit> {
    override fun write(message: Deposit, out: WireOut) {
        out.long(message.pence)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): Deposit = Deposit(input.long(), input.delivery())
}

private val tipCodec = object : MessageCodec<Tip> {
    override fun write(message: Tip, out: WireOut) {
        out.long(message.pence)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): Tip = Tip(input.long(), input.delivery())
}

private val quoteCodec = object : MessageCodec<Quote> {
    override fun write(message: Quote, out: WireOut) {
        out.reply(message.reply, Codecs.long)
        out.delivery(message.delivery)
    }

    override fun read(input: WireIn): Quote = Quote(input.reply(Codecs.long), input.delivery())
}

/** A producer as a ref, at the address it has while it runs. */
private fun producerAt(incarnation: Long) = object : ActorRef<Confirmed> {
    override val address = Address("shop-1@10.0.0.7:25520", "/user/producer-checkout", incarnation)

    override fun tell(message: Confirmed) = Unit
}

object OutboxSuite : Suite() {
    init {
        test(
            "a kept command reads back with a blank delivery, and redelivered it is the original but for the delivery",
        ) {
            val sent = Deposit(10, Delivery("checkout", "w-42", 7, producerAt(1)))

            val kept = depositCodec.outbox().decode(depositCodec.outbox().encode(sent))
            val again = Delivery("checkout", "w-42", 7, producerAt(2))

            kept shouldBe sent.copy(delivery = sent.delivery.blank())
            kept.delivery.confirmTo shouldBe Delivery.NoOne
            kept.redeliver(again) shouldBe Deposit(10, again)
        }

        test("a command that cannot redeliver itself is refused, and says why") {
            val tip = Tip(5, Delivery("checkout", "w-42", 1, producerAt(1)))

            shouldThrow<UnsupportedOperationException>({ tipCodec.outbox().encode(tip) }) { e ->
                e.message shouldContain "Tip is sent durably, so it must implement redeliver"
            }
        }

        test("a command that carries a reply is refused, since nothing would answer it after a crash") {
            val reply = object : Reply<Long> {
                override val address = Address("shop-1@10.0.0.7:25520", "/temp/ask-1", 1)

                override fun invoke(answer: Long) = Unit
            }

            shouldThrow<IllegalArgumentException>({
                quoteCodec.outbox().encode(Quote(reply, Delivery("checkout", "w-42", 1, producerAt(1))))
            }) { e -> e.message shouldContain "carries no reply" }
        }
    }
}
