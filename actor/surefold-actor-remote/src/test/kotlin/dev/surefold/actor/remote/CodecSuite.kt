package dev.surefold.actor.remote

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Confirmed
import dev.surefold.actor.Delivery
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.Reply
import dev.surefold.actor.protocolFaults
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow

private sealed interface Clinic

private data class Book(val pet: String, val grams: Int, val note: String?) : Clinic

private data class Weigh(val reply: Reply<Int>) : Clinic

private data class Refer(val to: ActorRef<Clinic>, val since: Long) : Clinic

private data object Close : Clinic

/** The clinic's messages, field by field, with a tag in front of each. */
private val clinicCodec = object : MessageCodec<Clinic> {
    override fun write(message: Clinic, out: WireOut) = when (message) {
        is Book -> {
            out.int(1)
            out.string(message.pet)
            out.int(message.grams)
            out.nullable(message.note, out::string)
        }

        is Weigh -> {
            out.int(2)
            out.reply(message.reply, Codecs.int)
        }

        is Refer -> {
            out.int(3)
            out.ref(message.to, this)
            out.long(message.since)
        }

        Close -> out.int(4)
    }

    override fun read(input: WireIn): Clinic = when (val tag = input.int()) {
        1 -> Book(input.string(), input.int(), input.nullable(input::string))
        2 -> Weigh(input.reply(Codecs.int))
        3 -> Refer(input.ref(this), input.long())
        4 -> Close
        else -> error("no clinic message has the tag $tag")
    }
}

/** Refs as a transport would make them: an address on the way out, and a ref for that address on the way in. */
private class AddressedRefs : Refs {
    val told = mutableListOf<Pair<Address, Any>>()

    override fun <M : Any> address(ref: ActorRef<M>, codec: MessageCodec<M>): Address = ref.address

    override fun <A : Any> address(reply: Reply<A>, answers: MessageCodec<A>): Address = reply.address

    override fun <M : Any> ref(address: Address, codec: MessageCodec<M>): ActorRef<M> = object : ActorRef<M> {
        override val address = address

        override fun tell(message: M) {
            told += address to message
        }
    }

    override fun <A : Any> reply(address: Address, answers: MessageCodec<A>): Reply<A> = object : Reply<A> {
        override val address = address

        override fun invoke(answer: A) {
            told += address to answer
        }
    }
}

private val there = Address("shop-2@10.0.0.7:25520", "/user/clinic", 7)

private fun Clinic.roundTrip(refs: Refs): Clinic = clinicCodec.decode(clinicCodec.encode(this, refs), refs)

object CodecSuite : Suite() {
    init {
        test("a keyed command crosses with its key, and its answer comes back through its reply, refusal included") {
            val refs = AddressedRefs()
            val keyed = Keyed.codec(Codecs.long, Codecs.string)
            val asked = refs.reply(Address("bank-1@10.0.0.6:25520", "/temp/ask-9", 1), Codecs.int)
            val answered = refs.reply(Address("bank-1@10.0.0.6:25520", "/temp/ask-10", 1), Codecs.int)
            val replies = listOf(asked, answered).map { reply ->
                object : Reply<KeyAnswer<String>> {
                    override val address = reply.address

                    override fun invoke(answer: KeyAnswer<String>) = Unit
                }
            }

            val apply = keyed.decode(keyed.encode(Keyed.Apply("t-1/debit", 400L, replies[0]), refs), refs)
            val applied = keyed.decode(keyed.encode(Keyed.Applied("t-1/debit", replies[1]), refs), refs)

            apply.shouldBeInstanceOf<Keyed.Apply<Long, String>> { sent ->
                sent.key shouldBe "t-1/debit"
                sent.command shouldBe 400L
                sent.reply(KeyAnswer.Refused("funds"))
                sent.reply.address shouldBe asked.address
            }
            applied.shouldBeInstanceOf<Keyed.Applied<String>> { asking ->
                asking.key shouldBe "t-1/debit"
                asking.reply(KeyAnswer.Accepted)
            }
            refs.told shouldContainExactly listOf(
                asked.address to KeyAnswer.Refused("funds"),
                answered.address to KeyAnswer.Accepted,
            )
        }

        test("a message of plain fields comes back equal, a missing note included") {
            val refs = AddressedRefs()
            Book("rex", 50, "limps").roundTrip(refs) shouldBe Book("rex", 50, "limps")
            Book("bo", 7, null).roundTrip(refs) shouldBe Book("bo", 7, null)
            Close.roundTrip(refs) shouldBe Close
        }

        test("a reply crosses as its address, and answering the one that comes back answers that address") {
            val refs = AddressedRefs()
            val asked = refs.reply(Address("shop-1@10.0.0.6:25520", "/temp/ask-3", 1), Codecs.int)

            Weigh(asked).roundTrip(refs).shouldBeInstanceOf<Weigh> { weigh ->
                weigh.reply(75)

                weigh.reply.address shouldBe asked.address
                refs.told shouldContainExactly listOf(asked.address to 75)
            }
        }

        test("a ref crosses as its address, incarnation included, and tells that address") {
            val refs = AddressedRefs()
            val sent = Refer(refs.ref(there, clinicCodec), since = 1_700_000_000_000)
            sent.roundTrip(refs).shouldBeInstanceOf<Refer> { refer ->
                refer.to.tell(Close)

                refer.since shouldBe 1_700_000_000_000
                refer.to.address shouldBe there
                refs.told shouldContainExactly listOf(there to Close)
            }
        }

        test("text past what a modified UTF-8 string holds, and outside the basic plane, comes back whole") {
            val long = "é".repeat(40_000) + "🐾"

            Book(long, 1, null).roundTrip(AddressedRefs()) shouldBe Book(long, 1, null)
        }

        test("reading past the end of a message is an error that says so") {
            val refs = AddressedRefs()
            val cut = clinicCodec.encode(Book("rex", 50, null), refs).copyOf(3)

            shouldThrow<WireException>({ clinicCodec.decode(cut, refs) }) { e ->
                e.message shouldBe "a message ended after 3 bytes, part way through what its codec reads"
            }
        }

        test("the answers most asks want come back as they went") {
            val refs = AddressedRefs()
            val longs = Codecs.list(Codecs.long)
            Codecs.int.decode(Codecs.int.encode(-7, refs), refs) shouldBe -7
            Codecs.string.decode(Codecs.string.encode("rex", refs), refs) shouldBe "rex"
            longs.decode(longs.encode(listOf(1L, 2L), refs), refs) shouldBe listOf(1L, 2L)
            Codecs.unit.decode(Codecs.unit.encode(Unit, refs), refs) shouldBe Unit
        }

        test("the clinic's messages could all cross") {
            protocolFaults<Clinic>().shouldBeEmpty()
        }

        test("a delivery crosses with its producer's address, and confirming the one that comes back tells it") {
            val refs = AddressedRefs()
            val producer = Address("shop-1@10.0.0.6:25520", "/user/producer-till", 3)
            val delivery = Delivery("till/1", "o-42", 9, refs.ref(producer, Codecs.confirmed))
            val carried = object : MessageCodec<Delivery> {
                override fun write(message: Delivery, out: WireOut) = out.delivery(message)

                override fun read(input: WireIn): Delivery = input.delivery()
            }

            val crossed = carried.decode(carried.encode(delivery, refs), refs)
            crossed.confirm()

            crossed.copy(confirmTo = delivery.confirmTo) shouldBe delivery
            crossed.confirmTo.address shouldBe producer
            refs.told shouldContainExactly listOf(producer to Confirmed("o-42", 9))
            Codecs.confirmed.decode(Codecs.confirmed.encode(Confirmed("o-42", 9), refs), refs) shouldBe
                Confirmed("o-42", 9)
        }
    }
}
