package dev.surefold.bank

import dev.surefold.actor.Delivered
import dev.surefold.actor.Delivery
import dev.surefold.actor.EventCodec
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.persistent
import dev.surefold.actor.remote.Codecs
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.delivery

internal sealed interface TransferMsg

/** Starts the transfer; [at] is when it was asked for, in epoch milliseconds, so its end can say how long it took. */
internal data class Start(val from: String, val to: String, val pence: Long, val at: Long = 0) : TransferMsg

internal data class DebitDone(val taken: Boolean) : TransferMsg

internal data object CreditDone : TransferMsg

/** Answers the transfer's phase now. */
internal data class Status(val reply: Reply<String>) : TransferMsg

/** A start, or an account's answer, as a producer sends it. */
internal data class ToTransfer(val command: TransferMsg, override val delivery: Delivery) :
    TransferMsg,
    Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

internal enum class Phase(val ended: Boolean = false) {
    New,
    Requested,
    Debited,
    Done(ended = true),
    Refused(ended = true),
}

internal data class Saga(
    val phase: Phase = Phase.New,
    val from: String = "",
    val to: String = "",
    val pence: Long = 0,
    val at: Long = 0,
)

internal sealed interface TransferEvent {
    data class Requested(val from: String, val to: String, val pence: Long, val at: Long) : TransferEvent

    data object Debited : TransferEvent

    data object Credited : TransferEvent

    data object Refused : TransferEvent
}

internal fun Saga.after(event: TransferEvent): Saga = when (event) {
    is TransferEvent.Requested -> Saga(Phase.Requested, event.from, event.to, event.pence, event.at)
    TransferEvent.Debited -> copy(phase = Phase.Debited)
    TransferEvent.Credited -> copy(phase = Phase.Done)
    TransferEvent.Refused -> copy(phase = Phase.Refused)
}

/**
 * The transfer [id], a saga: it debits the source through [send], which sends reliably, then credits the destination,
 * or ends refused, and tells [ended]. A repeated start or answer does again whatever the first did after its events
 * were written, since a crash may have lost it: [ended] may hear one transfer end more than once.
 */
internal fun transfer(id: String, send: (account: String, AccountMsg) -> Unit, ended: (String, Saga) -> Unit) =
    repeatable(
        persistent<TransferMsg, TransferEvent, Saga>(
            id = PersistenceId("transfer", id),
            empty = Saga(),
            codec = TransferEvents,
            command = { _, saga, command ->
                fun debit(s: Saga) = send(s.from, Debit(id, s.pence))
                fun credit(s: Saga) = send(s.to, Credit(id, s.pence))
                fun end(s: Saga) = ended(id, s)
                when (command) {
                    is Start -> when (saga.phase) {
                        Phase.New -> command.run { persist(TransferEvent.Requested(from, to, pence, at)) }.then(::debit)
                        Phase.Requested -> none().then(::debit)
                        Phase.Debited -> none().then(::credit)
                        Phase.Done, Phase.Refused -> none().then(::end)
                    }

                    is DebitDone -> when {
                        saga.phase == Phase.Requested && command.taken -> persist(TransferEvent.Debited).then(::credit)
                        saga.phase == Phase.Requested -> persist(TransferEvent.Refused).then(::end)
                        saga.phase == Phase.Debited -> none().then(::credit)
                        saga.phase.ended -> none().then(::end)
                        else -> none()
                    }

                    CreditDone -> when (saga.phase) {
                        Phase.Debited -> persist(TransferEvent.Credited).then(::end)
                        Phase.Done -> none().then(::end)
                        Phase.New, Phase.Requested, Phase.Refused -> none()
                    }

                    is Status -> none().then { command.reply(it.phase.name) }

                    is ToTransfer -> error("a command sent reliably reaches the transfer unwrapped")
                }
            },
            event = Saga::after,
        ),
    ) { message -> if (message is ToTransfer) message.command else message }

internal object TransferEvents : EventCodec<TransferEvent> {
    override fun encode(event: TransferEvent): ByteArray = when (event) {
        is TransferEvent.Requested -> "R|${event.from}|${event.to}|${event.pence}|${event.at}"
        TransferEvent.Debited -> "D"
        TransferEvent.Credited -> "C"
        TransferEvent.Refused -> "X"
    }.toByteArray()

    override fun decode(bytes: ByteArray): TransferEvent {
        val fields = String(bytes).split("|")
        return when (fields[0]) {
            // A start written before it carried its time has no fifth field, and is read as started at zero.
            "R" -> (fields + "0").let { (_, from, to, pence, at) ->
                TransferEvent.Requested(from, to, pence.toLong(), at.toLong())
            }

            "D" -> TransferEvent.Debited

            "C" -> TransferEvent.Credited

            "X" -> TransferEvent.Refused

            else -> error("no transfer event is written '${fields[0]}'")
        }
    }
}

internal object TransferCodec : MessageCodec<TransferMsg> {
    override fun write(message: TransferMsg, out: WireOut): Unit = when (message) {
        is Start -> {
            out.int(START)
            out.string(message.from)
            out.string(message.to)
            out.long(message.pence)
            out.long(message.at)
        }

        is DebitDone -> {
            out.int(DEBIT_DONE)
            out.boolean(message.taken)
        }

        CreditDone -> out.int(CREDIT_DONE)

        is Status -> {
            out.int(STATUS)
            out.reply(message.reply, Codecs.string)
        }

        is ToTransfer -> {
            out.int(SENT)
            write(message.command, out)
            out.delivery(message.delivery)
        }
    }

    override fun read(input: WireIn): TransferMsg = when (val tag = input.int()) {
        START -> Start(input.string(), input.string(), input.long(), input.long())
        DEBIT_DONE -> DebitDone(input.boolean())
        CREDIT_DONE -> CreditDone
        STATUS -> Status(input.reply(Codecs.string))
        SENT -> ToTransfer(read(input), input.delivery())
        else -> error("no transfer message has the tag $tag")
    }

    private const val START = 1
    private const val DEBIT_DONE = 2
    private const val CREDIT_DONE = 3
    private const val STATUS = 4
    private const val SENT = 5
}
