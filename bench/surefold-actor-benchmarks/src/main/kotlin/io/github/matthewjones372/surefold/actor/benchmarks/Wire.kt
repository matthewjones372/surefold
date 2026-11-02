package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.Delivered
import dev.surefold.actor.Delivery
import dev.surefold.actor.EventCodec
import dev.surefold.actor.Reply
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.delivery
import org.apache.pekko.actor.ExtendedActorSystem
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.ActorRefResolver
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.serialization.SerializerWithStringManifest
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

// The cluster benchmarks' messages, once per runtime with the same fields, each written by hand field by field: a
// surefold `MessageCodec` on one side and a Pekko serializer on the other, over the same `DataOutputStream`.

/** The tag [Locate] has in every protocol it is part of. */
private const val LOCATE = 3

internal sealed interface Shard

/** Counts itself on the burst's latch. */
internal data class Bump(val n: Int) : Shard

/** Answered with [n]. */
internal data class Bounce(val n: Int, val reply: Reply<Int>) : Shard

/** Answered with the name of the node the entity runs on. */
internal data class Locate(val reply: Reply<String>) : Shard, Account, Paying

internal sealed interface Account

/** Persisted as one event, and answered with the balance once it is written. */
internal data class Deposit(val pence: Long, val reply: Reply<Long>) : Account

internal sealed interface Paying

/** Sent reliably, and counted on the latch of the send waiting for it. */
internal data class Payment(val n: Int, override val delivery: Delivery) : Paying, Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

internal val shardCodec = object : MessageCodec<Shard> {
    override fun write(message: Shard, out: WireOut) = when (message) {
        is Bump -> {
            out.int(1)
            out.int(message.n)
        }

        is Bounce -> {
            out.int(2)
            out.int(message.n)
            out.reply(message.reply, Codecs.int)
        }

        is Locate -> {
            out.int(LOCATE)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Shard = when (val tag = input.int()) {
        1 -> Bump(input.int())
        2 -> Bounce(input.int(), input.reply(Codecs.int))
        LOCATE -> Locate(input.reply(Codecs.string))
        else -> error("no shard message has the tag $tag")
    }
}

internal val accountCodec = object : MessageCodec<Account> {
    override fun write(message: Account, out: WireOut) = when (message) {
        is Deposit -> {
            out.int(1)
            out.long(message.pence)
            out.reply(message.reply, Codecs.long)
        }

        is Locate -> {
            out.int(LOCATE)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Account = when (val tag = input.int()) {
        1 -> Deposit(input.long(), input.reply(Codecs.long))
        LOCATE -> Locate(input.reply(Codecs.string))
        else -> error("no account message has the tag $tag")
    }
}

/** An event as the pence paid in, in decimal text, as the cluster guide's is; Pekko's is written the same way. */
internal val deposited = object : EventCodec<Long> {
    override fun encode(event: Long): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

internal val payingCodec = object : MessageCodec<Paying> {
    override fun write(message: Paying, out: WireOut) = when (message) {
        is Payment -> {
            out.int(1)
            out.int(message.n)
            out.delivery(message.delivery)
        }

        is Locate -> {
            out.int(LOCATE)
            out.reply(message.reply, Codecs.string)
        }
    }

    override fun read(input: WireIn): Paying = when (val tag = input.int()) {
        1 -> Payment(input.int(), input.delivery())
        LOCATE -> Locate(input.reply(Codecs.string))
        else -> error("no paying message has the tag $tag")
    }
}

/** A topic's message: a price for [n], or, below zero, a probe that a subscriber is hearing. */
internal val priceCodec: MessageCodec<Int> = Codecs.int

/** What [WireSerializer] writes: every Pekko message a cluster benchmark sends between nodes. */
interface PekkoWire

sealed interface PekkoShard : PekkoWire

data class PekkoBump(val n: Int) : PekkoShard

data class PekkoBounce(val n: Int, val replyTo: ActorRef<Int>) : PekkoShard

data class PekkoLocate(val replyTo: ActorRef<String>) : PekkoShard, PekkoAccount, PekkoPaying

sealed interface PekkoAccount : PekkoWire

data class PekkoDeposit(val pence: Long, val replyTo: ActorRef<Long>) : PekkoAccount

/** The persistent entity's one event. */
data class PekkoDeposited(val pence: Long) : PekkoWire

sealed interface PekkoPaying : PekkoWire

data class PekkoPay(val n: Int) : PekkoPaying

/** A topic's message, as surefold's `Int` is: a price, or, below zero, a probe. */
data class PekkoPrice(val n: Int) : PekkoWire

/** Pekko's side of the benchmarks' codecs: a ref crosses as the string Pekko's resolver makes of it. */
class WireSerializer(system: ExtendedActorSystem) : SerializerWithStringManifest() {
    private val refs = ActorRefResolver.get(Adapter.toTyped(system))

    override fun identifier(): Int = IDENTIFIER

    override fun manifest(o: Any): String = when (o) {
        is PekkoBump -> "B"
        is PekkoBounce -> "E"
        is PekkoLocate -> "L"
        is PekkoDeposit -> "D"
        is PekkoDeposited -> "V"
        is PekkoPay -> "P"
        is PekkoPrice -> "R"
        else -> throw IllegalArgumentException("no manifest for ${o.javaClass}")
    }

    override fun toBinary(o: Any): ByteArray {
        val buffer = ByteArrayOutputStream()
        val out = DataOutputStream(buffer)
        when (o) {
            is PekkoBump -> out.writeInt(o.n)

            is PekkoBounce -> {
                out.writeInt(o.n)
                out.string(refs.toSerializationFormat(o.replyTo))
            }

            is PekkoLocate -> out.string(refs.toSerializationFormat(o.replyTo))

            is PekkoDeposit -> {
                out.writeLong(o.pence)
                out.string(refs.toSerializationFormat(o.replyTo))
            }

            is PekkoDeposited -> out.write(o.pence.toString().toByteArray())

            is PekkoPay -> out.writeInt(o.n)

            is PekkoPrice -> out.writeInt(o.n)

            else -> throw IllegalArgumentException("cannot write ${o.javaClass}")
        }
        return buffer.toByteArray()
    }

    override fun fromBinary(bytes: ByteArray, manifest: String): Any {
        val input = DataInputStream(ByteArrayInputStream(bytes))
        return when (manifest) {
            "B" -> PekkoBump(input.readInt())
            "E" -> PekkoBounce(input.readInt(), refs.resolveActorRef(input.string()))
            "L" -> PekkoLocate(refs.resolveActorRef(input.string()))
            "D" -> PekkoDeposit(input.readLong(), refs.resolveActorRef(input.string()))
            "V" -> PekkoDeposited(String(bytes).toLong())
            "P" -> PekkoPay(input.readInt())
            "R" -> PekkoPrice(input.readInt())
            else -> throw IllegalArgumentException("no message has the manifest $manifest")
        }
    }

    private companion object {
        const val IDENTIFIER = 920_092
    }
}

/** As surefold's `WireOut.string`: UTF-8 with its length in front. */
private fun DataOutputStream.string(value: String) {
    val bytes = value.toByteArray(Charsets.UTF_8)
    writeInt(bytes.size)
    write(bytes)
}

private fun DataInputStream.string(): String = String(readNBytes(readInt()), Charsets.UTF_8)
