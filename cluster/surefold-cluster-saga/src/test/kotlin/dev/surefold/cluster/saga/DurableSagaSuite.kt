package dev.surefold.cluster.saga

import dev.surefold.Settled
import dev.surefold.Shared
import dev.surefold.TestClock
import dev.surefold.actor.InMemoryJournal
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.StateCodec
import dev.surefold.actor.StoredEvent
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldThrow
import java.time.Instant
import kotlin.time.Duration.Companion.minutes

private object Text : StateCodec<String> {
    override fun encode(state: String): ByteArray = state.toByteArray()

    override fun decode(bytes: ByteArray): String = String(bytes)
}

private object Pence : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** A bank that remembers each key it applied, as a keyed entity would, and how often it was called. */
private class Bank {
    val applied = Shared(emptyMap<String, Long>())
    val calls = Shared(0)

    fun move(key: String, pence: Long): Long {
        calls.update { it + 1 }
        applied.update { if (key in it) it else it + (key to pence) }
        return applied.peek().getValue(key)
    }

    fun find(key: String): Settled<Long> = applied.peek()[key]?.let { Settled.Happened(it) } ?: Settled.DidNot
}

private fun transfer(bank: Bank) = durableSaga("transfer", Pence, Text, Text) { pence ->
    val debited = step("debit", Pence, { key -> bank.move(key, -pence) }, { key -> bank.find(key) })
    if (debited > 0) fail("never")
    val credited = step("credit", Pence, { key -> bank.move(key, pence) }, { key -> bank.find(key) })
    "moved $credited"
}

/** Throws, as a node dying would, instead of the append numbered [dieAt]; the appends before it are kept. */
private class Dying(private val inner: Journal, private val dieAt: Int) : Journal by inner {
    private var appends = 0

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        check(++appends != dieAt) { "the node died before append $dieAt" }
        return inner.append(id, expected, events)
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from)
}

object DurableSagaSuite : Suite() {
    init {
        test("a saga runs its steps in order and journals each one's beginning, end and the saga's answer") {
            val bank = Bank()
            val journal = InMemoryJournal()

            transfer(bank).run(journal, "t-1", 400) shouldBe "moved 400".right()

            bank.applied.peek() shouldBe mapOf("t-1/debit" to -400L, "t-1/credit" to 400L)
            journal.read(PersistenceId("transfer", "t-1")).size shouldBe 6
        }

        test("an ended saga run again answers as it ended and calls nothing") {
            val bank = Bank()
            val journal = InMemoryJournal()
            transfer(bank).run(journal, "t-1", 400)

            transfer(bank).run(journal, "t-1", 999) shouldBe "moved 400".right()

            bank.calls.peek() shouldBe 2
        }

        test("a declared fail ends the saga on the left, and a rerun answers the same without running") {
            val journal = InMemoryJournal()
            val refusing = durableSaga("transfer", Pence, Text, Text) { _ -> fail("funds") }

            refusing.run(journal, "t-2", 400) shouldBe "funds".left()
            durableSaga("transfer", Pence, Text, Text) { _ -> "ran" }.run(journal, "t-2", 400) shouldBe "funds".left()
        }

        test("a node that dies at any append leaves a saga that, run again, ends exactly as it would have") {
            val reference = Bank().let { bank ->
                transfer(bank).run(InMemoryJournal(), "t", 400) to bank.applied.peek()
            }
            for (dieAt in 1..6) {
                val bank = Bank()
                val journal = InMemoryJournal()
                shouldThrow<IllegalStateException> { transfer(bank).run(Dying(journal, dieAt), "t", 400) }

                transfer(bank).run(journal, "t", 400) shouldBe reference.first
                bank.applied.peek() shouldBe reference.second
            }
        }

        test("a step that began and has no end is settled by its check, not called again") {
            val bank = Bank()
            val journal = InMemoryJournal()
            // Dies before the debit's end is written, after the bank applied it.
            shouldThrow<IllegalStateException> { transfer(bank).run(Dying(journal, 3), "t", 400) }
            val callsBefore = bank.calls.peek()

            transfer(bank).run(journal, "t", 400)

            bank.calls.peek() shouldBe callsBefore + 1
        }

        test("a step that began, may have happened and has no check stops the saga Unsettled") {
            val calls = Shared(0)
            val unchecked = durableSaga("transfer", Pence, Text, Text) { pence ->
                step("debit", Pence, { _ ->
                    calls.update { it + 1 }
                    pence
                })
                "done"
            }
            val journal = InMemoryJournal()
            shouldThrow<IllegalStateException> { unchecked.run(Dying(journal, 3), "t", 400) }

            shouldThrow<Unsettled>({ unchecked.run(journal, "t", 400) }) { stopped -> stopped.step shouldBe "debit" }

            calls.peek() shouldBe 1
        }

        test("a resumed saga whose body calls another step than its journal holds stops Diverged") {
            val journal = InMemoryJournal()
            val bank = Bank()
            shouldThrow<IllegalStateException> { transfer(bank).run(Dying(journal, 4), "t", 400) }
            val changed = durableSaga("transfer", Pence, Text, Text) { pence ->
                step("fraud-check", Pence, { _ -> pence })
                "done"
            }

            shouldThrow<Diverged>({ changed.run(journal, "t", 400) }) { diverged ->
                diverged.called shouldBe "fraud-check"
            }
        }

        test("now and uuid answer the same after a restart as they did the first time") {
            val time = TestClock(Instant.parse("2026-11-04T12:00:00Z"))
            val seen = Shared(emptyList<String>())
            val stamped = durableSaga("stamp", Pence, Text, Text) { _ ->
                val at = now()
                val ref = uuid()
                seen.update { it + "$at $ref" }
                step("after", Pence, { _ -> 1L }, { _ -> Settled.Happened(1L) })
                "$at $ref"
            }
            val journal = InMemoryJournal()
            clock.locally(time) { shouldThrow<IllegalStateException> { stamped.run(Dying(journal, 4), "s", 0) } }

            time.adjust(5.minutes)
            val answer = clock.locally(time) { stamped.run(journal, "s", 0) }

            seen.peek().distinct().size shouldBe 1
            answer shouldBe seen.peek().first().right()
        }

        test("two steps with one name in a saga are refused") {
            val twice = durableSaga("twice", Pence, Text, Text) { _ ->
                step("a", Pence, { _ -> 1L })
                step("a", Pence, { _ -> 2L })
                "done"
            }

            shouldThrow<IllegalArgumentException> { twice.run(InMemoryJournal(), "x", 0) }
        }
    }
}
