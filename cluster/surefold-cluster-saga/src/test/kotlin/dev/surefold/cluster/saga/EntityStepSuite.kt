package dev.surefold.cluster.saga

import dev.surefold.TestClock
import dev.surefold.actor.InMemoryJournal
import dev.surefold.clock
import dev.surefold.cluster.Sharded
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds

/** Pays [pence] from `a` into `b`: a credit of `a` first, so an undo has something to take back. */
private fun payment(accounts: Sharded<Account>) =
    durableSaga("payment", Says, Says, Says) { pence ->
        val amount = pence.toLong()
        entityStep(
            "top-up",
            accounts,
            "a",
            refused = { "funds" },
            undo = { Move.Debit(amount) },
            within = 300.milliseconds,
        ) {
            Move.Credit(amount)
        }
        entityStep("debit-b", accounts, "b", refused = { "funds" }, within = 300.milliseconds) { Move.Debit(amount) }
        "paid $amount"
    }

/** One step: a credit of `a`. */
private fun credit(accounts: Sharded<Account>) =
    durableSaga("credit", Says, Says, Says) { pence ->
        entityStep("credit", accounts, "a", refused = { "funds" }, within = 300.milliseconds) {
            Move.Credit(pence.toLong())
        }
        "credited $pence"
    }

object EntityStepSuite : Suite() {
    init {
        test("an entityStep whose answer is dropped settles through Applied without a second command") {
            val seen = Seen()
            Branch(seen).use { bank ->
                seen.toLose.update { 1 }

                credit(bank.accounts).run(InMemoryJournal(), "t", "400") shouldBe "credited 400".right()

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/credit"))
                (seen.asked.peek() >= 1) shouldBe true
            }
        }

        test("a refusal fails the saga with its error and undoes the steps before it, not the one refused") {
            val seen = Seen()
            Branch(seen).use { bank ->
                payment(bank.accounts).run(InMemoryJournal(), "t", "400") shouldBe "funds".left()

                seen.applied.peek() shouldBe
                    mapOf("a" to listOf("t/top-up", "t/top-up/undo"), "b" to listOf("t/debit-b"))
            }
        }

        test("a step begun and not ended when its node died is settled by Applied on resume, sent once") {
            val seen = Seen()
            Branch(seen).use { bank ->
                val journal = InMemoryJournal()
                // Started, the step's time, Begun, then the node dies before the step's end.
                shouldThrow<IllegalStateException> { credit(bank.accounts).run(Crashing(journal, 4), "t", "400") }

                credit(bank.accounts).resume(journal, "t") shouldBe "credited 400".right()

                seen.applied.peek() shouldBe mapOf("a" to listOf("t/credit"))
            }
        }

        test("resume refuses a saga stuck past the window its entities remember a key for, and sends nothing") {
            val seen = Seen()
            Branch(seen).use { bank ->
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
