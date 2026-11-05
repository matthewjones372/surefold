package dev.surefold.actor

import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days

internal sealed interface Move {
    data class Debit(val pence: Long) : Move

    data class Credit(val pence: Long) : Move
}

internal sealed interface Moved {
    data class Debited(val pence: Long) : Moved

    data class Credited(val pence: Long) : Moved
}

internal data object Funds

internal val movedCodec =
    object : EventCodec<Moved> {
        override fun encode(event: Moved): ByteArray =
            when (event) {
                is Moved.Debited -> "D:${event.pence}"
                is Moved.Credited -> "C:${event.pence}"
            }.toByteArray()

        override fun decode(bytes: ByteArray): Moved {
            val (kind, pence) = String(bytes).split(":")
            return if (kind == "D") Moved.Debited(pence.toLong()) else Moved.Credited(pence.toLong())
        }
    }

internal val fundsCodec =
    object : EventCodec<Funds> {
        override fun encode(event: Funds): ByteArray = "funds".toByteArray()

        override fun decode(bytes: ByteArray): Funds = Funds
    }

internal fun accountOf(id: String) = PersistenceId("account", id)

internal fun account(
    id: String,
    remember: Duration = 30.days,
    snapshots: Snapshotting<Long>? = null,
) =
    keyed<Move, Moved, Long, Funds>(
        id = accountOf(id),
        remember = remember,
        snapshots = snapshots,
        empty = 0L,
        codec = movedCodec,
        refusals = fundsCodec,
        decide = { balance, move ->
            when (move) {
                is Move.Debit -> if (balance < move.pence) refuse(Funds) else accept(Moved.Debited(move.pence))
                is Move.Credit -> accept(Moved.Credited(move.pence))
            }
        },
        event = { balance, moved ->
            when (moved) {
                is Moved.Debited -> balance - moved.pence
                is Moved.Credited -> balance + moved.pence
            }
        },
    )

object KeyedSuite : Suite() {
    init {
        test("a command is applied and accepted, and its events are what the state is built from") {
            testActors {
                val a = spawn("a", account("a"))

                a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) } shouldBe KeyAnswer.Accepted.right()
                a.ask { Keyed.Apply("t-2/debit", Move.Debit(400), it) } shouldBe KeyAnswer.Accepted.right()

                a.state.value.value shouldBe 600L
            }
        }

        test("a key applied twice is applied once, and both copies get the same answer") {
            testActors {
                val a = spawn("a", account("a"))
                a.ask { Keyed.Apply("t-0/credit", Move.Credit(1_000), it) }

                val first = a.ask { Keyed.Apply("t-1/debit", Move.Debit(400), it) }
                val second = a.ask { Keyed.Apply("t-1/debit", Move.Debit(400), it) }

                first shouldBe KeyAnswer.Accepted.right()
                second shouldBe KeyAnswer.Accepted.right()
                a.state.value.value shouldBe 600L
            }
        }

        test("a refusal is remembered too: a repeat is refused again even once it would now be accepted") {
            testActors {
                val a = spawn("a", account("a"))

                a.ask { Keyed.Apply("t-1/debit", Move.Debit(400), it) } shouldBe KeyAnswer.Refused(Funds).right()
                a.ask { Keyed.Apply("t-2/credit", Move.Credit(1_000), it) }
                a.ask { Keyed.Apply("t-1/debit", Move.Debit(400), it) } shouldBe KeyAnswer.Refused(Funds).right()

                a.state.value.value shouldBe 1_000L
            }
        }

        test("Applied answers what a key got, and Unknown for a key never seen") {
            testActors {
                val a = spawn("a", account("a"))
                a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }
                a.ask { Keyed.Apply("t-2/debit", Move.Debit(5_000), it) }

                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-1/credit", it) } shouldBe KeyAnswer.Accepted.right()
                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-2/debit", it) } shouldBe KeyAnswer.Refused(Funds).right()
                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-3/debit", it) } shouldBe KeyAnswer.Unknown.right()
            }
        }

        test("keys and answers outlive a restart, written in the same append as the events") {
            testActors {
                val a = spawn("a", account("a"))
                a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) }
                a.ask { Keyed.Apply("t-2/debit", Move.Debit(5_000), it) }

                a.restart()

                a.ask { Keyed.Apply("t-1/credit", Move.Credit(1_000), it) } shouldBe KeyAnswer.Accepted.right()
                a.ask<KeyAnswer<Funds>> { Keyed.Applied("t-2/debit", it) } shouldBe KeyAnswer.Refused(Funds).right()
                a.state.value.value shouldBe 1_000L
                journal.events(accountOf("a"), keyedEvents(movedCodec, fundsCodec)).size shouldBe 3
            }
        }

        test("a refusal writes its key and nothing else") {
            testActors {
                val a = spawn("a", account("a"))
                a.ask { Keyed.Apply("t-1/debit", Move.Debit(400), it) }

                val written = journal.events(accountOf("a"), keyedEvents(movedCodec, fundsCodec))
                written.map { (it as KeyedEvent.Answered).key to it.refused } shouldContainExactly
                    listOf("t-1/debit" to Funds)
            }
        }
    }
}
