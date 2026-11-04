package dev.surefold.cluster.saga

import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.KeyAnswer
import dev.surefold.actor.Keyed
import dev.surefold.actor.StateCodec
import dev.surefold.actor.ask
import dev.surefold.cluster.Sharded
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Moves [pence] from one account to another. */
private data class Transfer(val from: String, val to: String, val pence: Long)

private object TransferCodec : StateCodec<Transfer> {
    override fun encode(state: Transfer): ByteArray = "${state.from}|${state.to}|${state.pence}".toByteArray()

    override fun decode(bytes: ByteArray): Transfer {
        val (from, to, pence) = String(bytes).split("|")
        return Transfer(from, to, pence.toLong())
    }
}

/** The test transfer of spec 0299: a debit undone by a credit back, then a credit, each on a keyed account. */
private fun transfers(accounts: Sharded<Account>) = durableSaga("transfer", TransferCodec, Says, Says) { move ->
    entityStep(
        "debit",
        accounts,
        move.from,
        refused = { "refused $it" },
        undo = { Move.Credit(move.pence) },
        within = ASK_WITHIN,
    ) { Move.Debit(move.pence) }
    entityStep("credit", accounts, move.to, refused = { "refused $it" }, within = ASK_WITHIN) {
        Move.Credit(move.pence)
    }
    "moved ${move.pence}"
}

/** Short, since a lost answer is waited out once each time it is lost. */
private val ASK_WITHIN = 50.milliseconds

private val NAMES = listOf("a", "b", "c")
private const val OPENING = 500L
private const val RUNS = 200

/** How [move] ends with no fault at all, and the balances it leaves. */
private fun Map<String, Long>.after(move: Transfer): Pair<Either<String, String>, Map<String, Long>> = when {
    getValue(move.from) < move.pence -> "refused ${Refusal.Overdrawn}".left() to this

    getValue(move.to) + move.pence > CAP -> "refused ${Refusal.Full}".left() to this

    else -> "moved ${move.pence}".right() to this + mapOf(
        move.from to getValue(move.from) - move.pence,
        move.to to getValue(move.to) + move.pence,
    )
}

object TransferSuite : Suite() {
    init {
        test("$RUNS seeded runs, crashed at an append, answers lost, commands sent twice, move money exactly once") {
            val seen = Seen()
            Branch(seen).use { bank ->
                val transfer = transfers(bank.accounts)
                NAMES.forEach { name ->
                    bank.accounts.entity(name).ask<Account, KeyAnswer<Refusal>>(5.seconds) {
                        Keyed.Apply("opening/$name", Move.Credit(OPENING), it)
                    } shouldBe KeyAnswer.Accepted.right()
                }
                val journal = InMemoryJournal()
                var model = NAMES.associateWith { OPENING }
                var endings = emptySet<String>()
                var crashes = 0

                repeat(RUNS) { run ->
                    val random = Random(run)
                    val (from, to) = NAMES.shuffled(random).take(2)
                    val move = Transfer(from, to, random.nextLong(1, 701))
                    // An append numbered 0 never comes, so a run drawing it does not crash.
                    val dieAt = random.nextInt(0, 10)
                    val (expected, balances) = model.after(move)
                    seen.faults.update { Faults(run, lose = 0.2, twice = 0.2) }

                    val id = "t-$run"
                    val first = try {
                        transfer.run(Crashing(journal, dieAt), id, move)
                    } catch (_: IllegalStateException) {
                        null
                    }
                    // Run again with the same id, as the waker does elsewhere: it replays what was written.
                    val ended = first ?: transfer.run(journal, id, move)
                    endings += ended.fold({ it }, { "moved" })
                    if (first == null) crashes++

                    withClue("run $run: $move, the node dying before append $dieAt") {
                        ended shouldBe expected
                        NAMES.associateWith(bank::balance) shouldBe balances
                    }
                    model = balances
                }
                NAMES.sumOf(bank::balance) shouldBe OPENING * NAMES.size
                // The seeds reach every ending, an undone debit among them, and crash some runs part way.
                endings shouldBe setOf("moved", "refused ${Refusal.Overdrawn}", "refused ${Refusal.Full}")
                (crashes > 0) shouldBe true
            }
        }
    }
}
