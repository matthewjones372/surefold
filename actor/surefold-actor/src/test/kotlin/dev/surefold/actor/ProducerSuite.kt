package dev.surefold.actor

import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import java.util.concurrent.Executors
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private data class Credited(val pence: Int)

private val creditedCodec = object : EventCodec<Credited> {
    override fun encode(event: Credited): ByteArray = "${event.pence}".toByteArray()

    override fun decode(bytes: ByteArray): Credited = Credited(String(bytes).toInt())
}

private sealed interface LedgerCommand

/** Credits [pence], sent reliably. */
private data class CreditLedger(val pence: Int, override val delivery: Delivery) :
    LedgerCommand,
    Delivered

/** Stops the ledger, sent plainly. */
private data object CloseLedger : LedgerCommand

/** Answers the balance, sent plainly. */
private data class LedgerBalance(val reply: Reply<Int>) : LedgerCommand

private fun ledgerOf(id: String) = PersistenceId("ledger", id)

private fun ledger(id: String) = delivered(
    persistent<LedgerCommand, Credited, Int>(
        id = ledgerOf(id),
        empty = 0,
        codec = creditedCodec,
        command = { _, balance, command ->
            when (command) {
                is CreditLedger -> persist(Credited(command.pence))
                CloseLedger -> stop()
                is LedgerBalance -> none().then { command.reply(balance) }
            }
        },
        event = { balance, credited -> balance + credited.pence },
    ),
)

/** Tells nobody: an entity whose node has gone. */
private fun <M : Any> void(): ActorRef<M> = object : ActorRef<M> {
    override val address = Address("test", "/void", 0)

    override fun tell(message: M) = Unit
}

object ProducerSuite : Suite() {
    init {
        test("commands sent while their entity is stopped and started again are each applied once, in order") {
            testActors {
                var ledger = spawn("ledger", ledger("l-1"))
                val producer = producer("till", resendAfter = 2.seconds) { ledger }

                val _ = producer.send("l-1") { CreditLedger(1, it) }
                ledger.send(CloseLedger)
                producer.send("l-1") { CreditLedger(2, it) } shouldBe Unit.right()
                val _ = producer.send("l-1") { CreditLedger(3, it) }
                ledger = spawn("ledger-again", ledger("l-1"))
                advance(2.seconds)
                val _ = producer.send("l-1") { CreditLedger(4, it) }

                journal.events(ledgerOf("l-1"), creditedCodec) shouldContainExactly (1..4).map(::Credited)
                ledger.state.value shouldBe 10
            }
        }

        test("a command whose confirmation is lost is sent again, applied once, and then the next is sent") {
            testActors {
                val ledger = spawn("ledger", ledger("l-1"))
                var lose = true
                val lossy = object : ActorRef<LedgerCommand> {
                    override val address = ledger.address

                    override fun tell(message: LedgerCommand) = ledger.tell(
                        if (lose && message is CreditLedger) {
                            lose = false
                            message.copy(delivery = message.delivery.copy(confirmTo = void()))
                        } else {
                            message
                        },
                    )
                }
                val producer = producer("till", resendAfter = 2.seconds) { lossy }

                val _ = producer.send("l-1") { CreditLedger(10, it) }
                val _ = producer.send("l-1") { CreditLedger(20, it) }
                journal.events(ledgerOf("l-1"), creditedCodec) shouldContainExactly listOf(Credited(10))

                advance(2.seconds)

                val both = listOf(Credited(10), Credited(20))
                journal.events(ledgerOf("l-1"), creditedCodec) shouldContainExactly both
            }
        }

        test("a producer keeping as many as it may answers Full, and has room again once one is confirmed") {
            testActors {
                var to: ActorRef<LedgerCommand> = void()
                val producer = producer("till", keep = 2, within = Duration.ZERO) { id ->
                    if (id ==
                        "l-1"
                    ) to else void()
                }

                producer.send("l-1") { CreditLedger(1, it) } shouldBe Unit.right()
                producer.send("l-2") { CreditLedger(2, it) } shouldBe Unit.right()
                producer.send("l-3") { CreditLedger(3, it) } shouldBe Full.left()

                val ledger = spawn("ledger", ledger("l-1"))
                to = ledger
                advance(2.seconds)

                producer.send("l-3") { CreditLedger(3, it) } shouldBe Unit.right()
                ledger.state.value shouldBe 1
            }
        }

        test("on threads, commands from four senders to ten passivating entities are each applied once") {
            val journal = InMemoryJournal()
            val balances = scope {
                journal(journal)
                val ledgers = spawn("ledgers", entities(passivateAfter = 1.minutes) { id -> ledger(id) })
                val producer = producer("till", keep = 16) { id -> ledgers.entity(id) }
                Executors.newFixedThreadPool(4).use { senders ->
                    repeat(4) { sender ->
                        senders.execute {
                            for (n in 1..100) producer.send("l-${(sender * 100 + n) % 10}") { CreditLedger(n, it) }
                        }
                    }
                }
                awaitIdle()
                (0 until 10).map { n -> ledgers.entity("l-$n").ask(1.minutes) { LedgerBalance(it) }.getOrNull()!! }
            }

            balances.sum() shouldBe 4 * (1..100).sum()
            (0 until 10).sumOf { journal.events(ledgerOf("l-$it"), creditedCodec).size } shouldBe 400
        }

        test("a scope that closes straight after sending waits for every command to be confirmed first") {
            val journal = InMemoryJournal()

            val _ = scope {
                journal(journal)
                val ledgers = spawn("ledgers", entities(passivateAfter = 1.minutes) { id -> ledger(id) })
                val producer = producer("till") { id -> ledgers.entity(id) }
                for (n in 1..1_000) producer.send("l-${n % 10}") { CreditLedger(1, it) }
            }

            (0 until 10).sumOf { journal.events(ledgerOf("l-$it"), creditedCodec).size } shouldBe 1_000
        }

        test("a command that fails to build throws to its sender, keeps nothing, and the producer goes on") {
            testActors {
                val ledger = spawn("ledger", ledger("l-1"))
                val producer = producer("till", keep = 1, within = Duration.ZERO) { ledger }

                shouldThrow<IllegalStateException> { producer.send("l-1") { error("no such till") } }
                producer.send("l-1") { CreditLedger(5, it) } shouldBe Unit.right()

                ledger.state.value shouldBe 5
                ledger.state.delivered.values.single() shouldBe 1L
                producer.drain(Duration.ZERO) shouldBe true
            }
        }
    }
}
