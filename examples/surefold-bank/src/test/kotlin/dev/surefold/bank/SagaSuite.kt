package dev.surefold.bank

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Confirmed
import dev.surefold.actor.Delivery
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.TestActor
import dev.surefold.actor.TestActors
import dev.surefold.actor.events
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.outbox
import dev.surefold.actor.testActors
import dev.surefold.fail.map
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Two accounts and the transfers between them, each sending straight to the other rather than through a producer. */
private class Bank(val actors: TestActors, private val answering: Boolean = true) {
    val answers = CopyOnWriteArrayList<Pair<String, TransferMsg>>()
    private val accounts = ConcurrentHashMap<String, TestActor<AccountMsg, *, *>>()
    private val transfers = ConcurrentHashMap<String, TestActor<TransferMsg, *, *>>()
    val ended = CopyOnWriteArrayList<Pair<String, Phase>>()

    fun account(id: String): TestActor<AccountMsg, *, *> = accounts.computeIfAbsent(id) {
        actors.spawn(
            "account-$id",
            account(id) { to, answer ->
                answers += to to answer
                if (answering) transfer(to).tell(answer)
            },
        )
    }

    fun transfer(id: String): TestActor<TransferMsg, *, *> = transfers.computeIfAbsent(id) {
        actors.spawn(
            "transfer-$id",
            transfer(id, { account, command -> account(account).tell(command) }) { transfer, saga ->
                ended +=
                    transfer to saga.phase
            },
        )
    }

    fun open(id: String, pence: Long) = account(id).ask<Long> { Open(pence, it) }

    fun balance(id: String) = account(id).ask<Statement> { Balance(it) }.map { it.balance }

    fun status(transfer: String) = transfer(transfer).ask<String> { Status(it) }
}

private class Confirms : ActorRef<Confirmed> {
    val confirmed = CopyOnWriteArrayList<Confirmed>()
    override val address = Address("test", "/confirms", 1)

    override fun tell(message: Confirmed) {
        confirmed += message
    }
}

private fun <M : Any> roundTrip(codec: MessageCodec<M>, message: M): M = codec.outbox().let {
    it.decode(it.encode(message))
}

object SagaSuite : Suite() {
    init {
        test("a transfer debits one account, credits the other and ends done") {
            testActors {
                val bank = Bank(this)
                bank.open("a", 100)
                bank.open("b", 5)

                bank.transfer("t-1").tell(Start("a", "b", 30))

                bank.status("t-1") shouldBe "Done".right()
                bank.balance("a") shouldBe 70L.right()
                bank.balance("b") shouldBe 35L.right()
            }
        }

        test("a debit below zero is refused and moves nothing") {
            testActors {
                val bank = Bank(this)
                bank.open("a", 10)
                bank.open("b", 0)

                bank.transfer("t-1").tell(Start("a", "b", 11))

                bank.status("t-1") shouldBe "Refused".right()
                bank.balance("a") shouldBe 10L.right()
                bank.balance("b") shouldBe 0L.right()
                journal.events(PersistenceId("account", "a"), AccountEvents).last() shouldBe
                    AccountEvent.Refused("t-1", 11)
            }
        }

        test("a transfer is heard to end once it has, and again each time an answer to it is repeated") {
            testActors {
                val bank = Bank(this, answering = false)
                bank.open("a", 50)
                bank.open("b", 0)
                val transfer = bank.transfer("t-1")
                transfer.tell(Start("a", "b", 50))
                transfer.tell(DebitDone(taken = true))
                bank.ended.toList() shouldBe emptyList()

                repeat(2) { transfer.tell(CreditDone) }

                bank.ended shouldBe List(2) { "t-1" to Phase.Done }
                bank.status("t-1") shouldBe "Done".right()
            }
        }

        test("answers lost on the way are asked for again, and each step moves the money once") {
            testActors {
                val bank = Bank(this, answering = false)
                bank.open("a", 100)
                bank.open("b", 0)
                val transfer = bank.transfer("t-1")

                repeat(2) { transfer.tell(Start("a", "b", 40)) }
                repeat(2) { transfer.tell(DebitDone(taken = true)) }
                transfer.tell(CreditDone)
                transfer.tell(DebitDone(taken = true))

                val asked = List(2) { "t-1" to DebitDone(taken = true) } + List(2) { "t-1" to CreditDone }
                bank.answers shouldBe asked
                bank.balance("a") shouldBe 60L.right()
                bank.balance("b") shouldBe 40L.right()
                bank.status("t-1") shouldBe "Done".right()
                bank.ended shouldBe List(2) { "t-1" to Phase.Done }
            }
        }

        test("a debit delivered twice is answered twice, confirmed twice and taken once") {
            testActors {
                val bank = Bank(this)
                val confirms = Confirms()
                bank.open("a", 100)
                val delivery = Delivery("saga-n1", "a", 1, confirms)

                repeat(2) { bank.account("a").tell(ToAccount(Debit("t-1", 25), delivery)) }

                val answered = List(2) { "t-1" to DebitDone(taken = true) }
                bank.balance("a") shouldBe 75L.right()
                bank.answers.filter { it.first == "t-1" } shouldBe answered
                confirms.confirmed shouldBe List(2) { Confirmed("a", 1) }
            }
        }

        test("a statement shows the fifty latest movements, newest first") {
            testActors {
                val bank = Bank(this)
                bank.open("a", 2_000)
                bank.open("b", 0)

                (1..55).forEach { bank.transfer("t-$it").tell(Start("a", "b", it.toLong())) }

                val statement = bank.account("a").ask<Statement> { Balance(it) }.getOrNull()
                statement?.balance shouldBe 2_000L - (1..55).sum()
                statement?.movements shouldBe (55 downTo 6).map { Movement("t-$it", -it.toLong()) }
            }
        }

        test("a start written before it carried its time is read back as started at zero") {
            TransferEvents.decode("R|a|b|5".toByteArray()) shouldBe TransferEvent.Requested("a", "b", 5, at = 0)
        }

        test("every message crosses the wire and every event the journal as it was") {
            val delivery = Delivery("saga-n1", "a", 3, Delivery.NoOne)
            val accounts = listOf(Debit("t", 1), Credit("t", 2), ToAccount(Credit("t", 4), delivery))
            val transfers =
                listOf(Start("a", "b", 3, 9), DebitDone(false), CreditDone, ToTransfer(CreditDone, delivery))
            val statement =
                Statement(open = true, balance = 3, movements = listOf(Movement("t", -1), Movement("u", 4)))
            val events = listOf(AccountEvent.Opened(1), AccountEvent.Debited("t", 2), AccountEvent.Credited("t", 3))
            val steps = listOf(TransferEvent.Requested("a", "b", 1, 7), TransferEvent.Debited, TransferEvent.Credited)
            accounts.forAll { roundTrip(AccountCodec, it) shouldBe it }
            transfers.forAll { roundTrip(TransferCodec, it) shouldBe it }
            roundTrip(StatementCodec, statement) shouldBe statement
            events.forAll { AccountEvents.decode(AccountEvents.encode(it)) shouldBe it }
            steps.forAll { TransferEvents.decode(TransferEvents.encode(it)) shouldBe it }
        }
    }
}
