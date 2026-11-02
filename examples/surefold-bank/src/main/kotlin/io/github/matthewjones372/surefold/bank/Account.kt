package io.github.matthewjones372.surefold.bank

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

internal sealed interface AccountMsg

/** Opens the account with [pence] in it unless it is open already, and answers its balance. */
internal data class Open(val pence: Long, val reply: Reply<Long>) : AccountMsg

/** Answers the account's statement: whether it is open, its balance and its latest movements. */
internal data class Balance(val reply: Reply<Statement>) : AccountMsg

/** Money in or out of an account: [pence] is negative for a debit. */
internal data class Movement(val transfer: String, val pence: Long)

internal data class Statement(val open: Boolean, val balance: Long, val movements: List<Movement>)

internal data class Debit(val transfer: String, val pence: Long) : AccountMsg

internal data class Credit(val transfer: String, val pence: Long) : AccountMsg

/** A debit or a credit as a transfer sends it, through a producer. */
internal data class ToAccount(val command: AccountMsg, override val delivery: Delivery) :
    AccountMsg,
    Delivered {
    override fun redeliver(delivery: Delivery) = copy(delivery = delivery)
}

internal sealed interface AccountEvent {
    data class Opened(val pence: Long) : AccountEvent

    data class Debited(val transfer: String, val pence: Long) : AccountEvent

    data class Refused(val transfer: String, val pence: Long) : AccountEvent

    data class Credited(val transfer: String, val pence: Long) : AccountEvent
}

/** An account's state: what it holds, and every transfer it has answered, so a repeat is answered the same way. */
internal data class Ledger(
    val open: Boolean = false,
    val balance: Long = 0,
    val debits: Map<String, Boolean> = emptyMap(),
    val credits: Set<String> = emptySet(),
    val movements: List<Movement> = emptyList(),
) {
    fun moved(transfer: String, pence: Long) = (listOf(Movement(transfer, pence)) + movements).take(MOVEMENTS)
}

/** How many of an account's movements its statement shows, newest first. */
private const val MOVEMENTS = 50

internal fun Ledger.after(event: AccountEvent): Ledger = when (event) {
    is AccountEvent.Opened -> copy(open = true, balance = balance + event.pence)

    is AccountEvent.Debited -> copy(
        balance = balance - event.pence,
        debits = debits + (event.transfer to true),
        movements = moved(event.transfer, -event.pence),
    )

    is AccountEvent.Refused -> copy(debits = debits + (event.transfer to false))

    is AccountEvent.Credited -> copy(
        balance = balance + event.pence,
        credits = credits + event.transfer,
        movements = moved(event.transfer, event.pence),
    )
}

/** The account [id]. It tells each transfer how its debit or credit went through [answer], which sends reliably. */
internal fun account(id: String, answer: (transfer: String, TransferMsg) -> Unit) = repeatable(
    persistent<AccountMsg, AccountEvent, Ledger>(
        id = PersistenceId("account", id),
        empty = Ledger(),
        codec = AccountEvents,
        command = { _, ledger, command ->
            when (command) {
                is Open -> (if (ledger.open) none() else persist(AccountEvent.Opened(command.pence)))
                    .then { command.reply(it.balance) }

                is Balance -> none().then { command.reply(Statement(it.open, it.balance, it.movements)) }

                is Debit -> when {
                    command.transfer in ledger.debits -> none()

                    ledger.open && ledger.balance >= command.pence ->
                        persist(AccountEvent.Debited(command.transfer, command.pence))

                    else -> persist(AccountEvent.Refused(command.transfer, command.pence))
                }.then { answer(command.transfer, DebitDone(it.debits.getValue(command.transfer))) }

                is Credit -> if (command.transfer in ledger.credits) {
                    none()
                } else {
                    persist(AccountEvent.Credited(command.transfer, command.pence))
                }.then { answer(command.transfer, CreditDone) }

                is ToAccount -> error("a command sent reliably reaches the account unwrapped")
            }
        },
        event = Ledger::after,
    ),
) { message -> if (message is ToAccount) message.command else message }

internal object AccountEvents : EventCodec<AccountEvent> {
    override fun encode(event: AccountEvent): ByteArray = when (event) {
        is AccountEvent.Opened -> "O|${event.pence}"
        is AccountEvent.Debited -> "D|${event.transfer}|${event.pence}"
        is AccountEvent.Refused -> "R|${event.transfer}|${event.pence}"
        is AccountEvent.Credited -> "C|${event.transfer}|${event.pence}"
    }.toByteArray()

    override fun decode(bytes: ByteArray): AccountEvent {
        val fields = String(bytes).split("|")
        return when (fields[0]) {
            "O" -> AccountEvent.Opened(fields[1].toLong())
            "D" -> AccountEvent.Debited(fields[1], fields[2].toLong())
            "R" -> AccountEvent.Refused(fields[1], fields[2].toLong())
            "C" -> AccountEvent.Credited(fields[1], fields[2].toLong())
            else -> error("no account event is written '${fields[0]}'")
        }
    }
}

internal object AccountCodec : MessageCodec<AccountMsg> {
    override fun write(message: AccountMsg, out: WireOut): Unit = when (message) {
        is Open -> {
            out.int(OPEN)
            out.long(message.pence)
            out.reply(message.reply, Codecs.long)
        }

        is Balance -> {
            out.int(BALANCE)
            out.reply(message.reply, StatementCodec)
        }

        is Debit -> {
            out.int(DEBIT)
            out.string(message.transfer)
            out.long(message.pence)
        }

        is Credit -> {
            out.int(CREDIT)
            out.string(message.transfer)
            out.long(message.pence)
        }

        is ToAccount -> {
            out.int(SENT)
            write(message.command, out)
            out.delivery(message.delivery)
        }
    }

    override fun read(input: WireIn): AccountMsg = when (val tag = input.int()) {
        OPEN -> Open(input.long(), input.reply(Codecs.long))
        BALANCE -> Balance(input.reply(StatementCodec))
        DEBIT -> Debit(input.string(), input.long())
        CREDIT -> Credit(input.string(), input.long())
        SENT -> ToAccount(read(input), input.delivery())
        else -> error("no account message has the tag $tag")
    }

    private const val OPEN = 1
    private const val BALANCE = 2
    private const val DEBIT = 3
    private const val CREDIT = 4
    private const val SENT = 5
}

internal object StatementCodec : MessageCodec<Statement> {
    override fun write(message: Statement, out: WireOut) {
        out.boolean(message.open)
        out.long(message.balance)
        out.int(message.movements.size)
        message.movements.forEach {
            out.string(it.transfer)
            out.long(it.pence)
        }
    }

    override fun read(input: WireIn): Statement =
        Statement(input.boolean(), input.long(), List(input.int()) { Movement(input.string(), input.long()) })
}
