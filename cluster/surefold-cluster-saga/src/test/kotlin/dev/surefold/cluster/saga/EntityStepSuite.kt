package dev.surefold.cluster.saga

import dev.surefold.Promise
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.EventCodec
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Reply
import dev.surefold.actor.StateCodec
import dev.surefold.actor.StoredEvent
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.journal
import dev.surefold.actor.keyed
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.remote.codec
import dev.surefold.actor.remote.freePort
import dev.surefold.actor.remote.node
import dev.surefold.clock
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.Sharded
import dev.surefold.cluster.Status
import dev.surefold.cluster.cluster
import dev.surefold.cluster.sharding
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import dev.surefold.scope
import dev.surefold.surefoldFuture
import dev.surefold.test.eventually
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

private sealed interface Move {
    data class Debit(val pence: Long) : Move

    data class Credit(val pence: Long) : Move
}

private data object Funds

private object MoveCodec : MessageCodec<Move> {
    override fun write(message: Move, out: WireOut) {
        out.boolean(message is Move.Debit)
        out.long(
            when (message) {
                is Move.Debit -> message.pence
                is Move.Credit -> message.pence
            },
        )
    }

    override fun read(input: WireIn): Move =
        if (input.boolean()) Move.Debit(input.long()) else Move.Credit(input.long())
}

private object FundsWire : MessageCodec<Funds> {
    override fun write(message: Funds, out: WireOut) = Unit

    override fun read(input: WireIn): Funds = Funds
}

private object Moves : EventCodec<Move> {
    override fun encode(event: Move): ByteArray = MoveText.encode(event)

    override fun decode(bytes: ByteArray): Move = MoveText.decode(bytes)
}

private object MoveText : StateCodec<Move> {
    override fun encode(state: Move): ByteArray = when (state) {
        is Move.Debit -> "D:${state.pence}"
        is Move.Credit -> "C:${state.pence}"
    }.toByteArray()

    override fun decode(bytes: ByteArray): Move {
        val (kind, pence) = String(bytes).split(":")
        return if (kind == "D") Move.Debit(pence.toLong()) else Move.Credit(pence.toLong())
    }
}

private object FundsCodec : EventCodec<Funds> {
    override fun encode(event: Funds): ByteArray = "funds".toByteArray()

    override fun decode(bytes: ByteArray): Funds = Funds
}

private object Says : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private typealias Account = Keyed<Move, Funds>

/** A keyed account whose balance can't go below nothing. */
private fun account(id: String) = keyed<Move, Move, Long, Funds>(
    id = PersistenceId("account", id),
    empty = 0L,
    codec = Moves,
    refusals = FundsCodec,
    decide = { balance, move ->
        when (move) {
            is Move.Debit -> if (balance < move.pence) refuse(Funds) else accept(move)
            is Move.Credit -> accept(move)
        }
    },
    event = { balance, move ->
        when (move) {
            is Move.Debit -> balance - move.pence
            is Move.Credit -> balance + move.pence
        }
    },
)

/** An answer nobody hears, as one lost on its way back. */
private object Lost : Reply<KeyAnswer<Funds>> {
    override val address = Address("local", "/lost", 0)

    override fun invoke(answer: KeyAnswer<Funds>) = Unit
}

/** What each account was sent: every key it was told to apply, and how often it was asked what a key got. */
private class Seen {
    val applied = Shared(emptyMap<String, List<String>>())
    val asked = Shared(0)

    /** How many more answers to an apply are lost before they reach the step. */
    val toLose = Shared(0)

    /** The account [id], in front of which every message is counted, and an answer lost while [toLose] says so. */
    fun account(id: String) = behaviour<Account, ActorRef<Account>?>(null) { ctx, child, message ->
        val inner = child ?: ctx.spawn("keyed", account(id))
        val passed = when (message) {
            is Keyed.Apply -> {
                applied.update { it + (id to it[id].orEmpty() + message.key) }
                val lose = toLose.modify { left -> (left - 1).coerceAtLeast(0) to (left > 0) }
                if (lose) message.copy(reply = Lost) else message
            }

            is Keyed.Applied -> message.also { asked.update { it + 1 } }
        }
        inner.tell(passed)
        become(inner)
    }
}

/** A one-node cluster with the accounts sharded on it, on a scope of its own, until [close]. */
private class Bank(seen: Seen) : AutoCloseable {
    private val done = Promise<Nothing, Unit>()
    private val opened = Promise<Throwable, Sharded<Account>>()
    private val running = surefoldFuture {
        scope {
            journal(InMemoryJournal())
            val port = freePort()
            val gossip = Gossiping(probeEvery = 200.milliseconds, ackWithin = 100.milliseconds, formAfter = 200.milliseconds)
            val cluster = cluster(node("bank", port), Discovery.static(Node("", "127.0.0.1", port)), gossip)
            val accounts = cluster.sharding("account", Keyed.codec(MoveCodec, FundsWire), 1.minutes, entity = seen::account)
            eventually(30.seconds) { check(cluster.view.members.any { it.status == Status.Up }) { "the bank is not up" } }
            opened.succeed(accounts)
            done.await()
        }
    }.whenComplete { _, thrown -> thrown?.let(opened::fail) }

    val accounts: Sharded<Account>
        get() = opened.await().fold({ throw IllegalStateException("the bank did not open", it) }, { it })

    override fun close() {
        done.succeed(Unit)
        running.join()
    }
}

/** Throws, as a node dying would, instead of the append numbered [dieAt]; the appends before it are kept. */
private class Crashing(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        check(++appends != dieAt) { "the node died before append $dieAt" }
        return inner.append(id, expected, events)
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from)
}

/** Pays [pence] from `a` into `b`: a credit of `a` first, so an undo has something to take back. */
private fun payment(accounts: Sharded<Account>) = durableSaga("payment", Says, Says, Says) { pence ->
    val amount = pence.toLong()
    entityStep("top-up", accounts, "a", refused = { "funds" }, undo = { Move.Debit(amount) }, within = 300.milliseconds) {
        Move.Credit(amount)
    }
    entityStep("debit-b", accounts, "b", refused = { "funds" }, within = 300.milliseconds) { Move.Debit(amount) }
    "paid $amount"
}

/** One step: a credit of `a`. */
private fun credit(accounts: Sharded<Account>) = durableSaga("credit", Says, Says, Says) { pence ->
    entityStep("credit", accounts, "a", refused = { "funds" }, within = 300.milliseconds) {
        Move.Credit(pence.toLong())
    }
    "credited $pence"
}

object EntityStepSuite : Suite() {
    init {
        test("an entityStep whose answer is dropped settles through Applied without a second command") {
            val seen = Seen()
            Bank(seen).use { bank ->
                seen.toLose.update { 1 }

                credit(bank.accounts).run(InMemoryJournal(), "t", "400") shouldBe "credited 400".right()

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/credit"))
                (seen.asked.peek() >= 1) shouldBe true
            }
        }

        test("a refusal fails the saga with its error and undoes the steps before it, not the one refused") {
            val seen = Seen()
            Bank(seen).use { bank ->
                payment(bank.accounts).run(InMemoryJournal(), "t", "400") shouldBe "funds".left()

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/top-up", "t/top-up/undo"), "b" to listOf("t/debit-b"))
            }
        }

        test("a step begun and not ended when its node died is settled by Applied on resume, sent once") {
            val seen = Seen()
            Bank(seen).use { bank ->
                val journal = InMemoryJournal()
                // Started, the step's time, Begun, then the node dies before the step's end.
                shouldThrow<IllegalStateException> { credit(bank.accounts).run(Crashing(journal, 4), "t", "400") }

                credit(bank.accounts).resume(journal, "t") shouldBe "credited 400".right()

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/credit"))
            }
        }

        test("resume refuses a saga stuck past the window its entities remember a key for, and sends nothing") {
            val seen = Seen()
            Bank(seen).use { bank ->
                val journal = InMemoryJournal()
                val time = TestClock(Instant.parse("2026-11-04T12:00:00Z"))
                clock.locally(time) {
                    shouldThrow<IllegalStateException> { credit(bank.accounts).run(Crashing(journal, 4), "t", "400") }
                }

                time.adjust(31.days)
                clock.locally(time) {
                    shouldThrow<Outlived>({ credit(bank.accounts).resume(journal, "t") }) { it.step shouldBe "credit" }
                }

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/credit"))
                seen.asked.peek() shouldBe 0
            }
        }
    }
}
