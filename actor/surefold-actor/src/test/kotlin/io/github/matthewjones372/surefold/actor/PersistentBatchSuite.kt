package io.github.matthewjones372.surefold.actor

import dev.surefold.Schedule
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import dev.surefold.scope
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private val batchPence = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val batchTotal = object : StateCodec<Int> {
    override fun encode(state: Int): ByteArray = "$state".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private sealed interface BatchTill

/** Pays [batchPence] in, and says what the batchTill then holds. */
private data class BatchRing(val batchPence: Int, val totals: MutableList<Int>? = null) : BatchTill

/** Pays in reliably, and says nothing. */
private data class BatchDeposit(val batchPence: Int, override val delivery: Delivery) :
    BatchTill,
    Delivered

private data object BatchLook : BatchTill

private data object BatchClose : BatchTill

private data class BatchCount(val reply: Reply<Int>) : BatchTill

private val batchTillId = PersistenceId("batchTill", "t-1")

/** Counts the appends that reach the journal, and fails the ones [failing] names with a conflict. */
private class AppendCounting(
    private val kept: Journal = InMemoryJournal(),
    private val failing: Set<Int> = emptySet(),
) : Journal by kept {
    val appends = AtomicInteger()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        if (appends.incrementAndGet() in failing) JournalConflict(id, expected, expected + 1).left()
        else kept.append(id, expected, events)
}

/** A batchTill that holds its first message until [opened], so every message told before then is waiting at once. */
private fun batchTill(batch: Int, opened: CountDownLatch, every: Int? = null) = delivered(
    persistent<BatchTill, Int, Int>(
        id = batchTillId,
        empty = 0,
        codec = batchPence,
        command = { _, _, command ->
            when (command) {
                is BatchRing -> persist(command.batchPence).then { after -> command.totals?.add(after) }
                is BatchDeposit -> persist(command.batchPence)
                BatchLook -> unhandled()
                BatchClose -> stop()
                is BatchCount -> none().then { now -> command.reply(now) }
            }
        },
        event = { sum, paid -> sum + paid },
        snapshots = every?.let { every(it, batchTotal) },
        batch = batch,
    ),
).onStart { opened.await() }

object PersistentBatchSuite : Suite() {
    private fun rung(batch: Int, n: Int): Pair<AppendCounting, List<Int>> {
        val journal = AppendCounting()
        val opened = CountDownLatch(1)
        val totals = CopyOnWriteArrayList<Int>()
        val _ = scope {
            journal(journal)
            val batchTill = spawn("batchTill", batchTill(batch, opened))
            (1..n).forEach { batchTill.tell(BatchRing(it, totals)) }
            opened.countDown()
            awaitIdle()
        }
        return journal to totals
    }

    init {
        test("a thousand commands waiting at once are written in a handful of appends") {
            val (journal, _) = rung(batch = 64, n = 1_000)
            journal.appends.get() shouldBeLessThanOrEqual 20
            journal.events(batchTillId, batchPence) shouldContainExactly (1..1_000).toList()
        }

        test("batched and one at a time end in the same state and the same journal") {
            val (batched, batchedTotals) = rung(batch = 64, n = 300)
            val (single, singleTotals) = rung(batch = 1, n = 300)
            val events = batched.events(batchTillId, batchPence)
            withClue("one at a time appends once per command") { single.appends.get() shouldBe 300 }
            events shouldContainExactly single.events(batchTillId, batchPence)
            batchedTotals shouldContainExactly singleTotals
        }

        test("each command's then runs in order, with the state its own command left") {
            val (_, totals) = rung(batch = 64, n = 10)
            totals shouldContainExactly (1..10).runningFold(0, Int::plus).drop(1)
        }

        test("a delivery told twice in one batch is written once") {
            val journal = AppendCounting()
            val opened = CountDownLatch(1)
            val confirmed = CopyOnWriteArrayList<Confirmed>()
            val counted = scope {
                journal(journal)
                val confirms = spawn(
                    "confirms",
                    behaviour<Confirmed, Unit>(Unit) { _, _, confirmation ->
                        confirmed += confirmation
                        stay()
                    },
                )
                val batchTill = spawn("batchTill", batchTill(batch = 64, opened))
                val first = Delivery("checkout", "t-1", 1, confirms)
                batchTill.tell(BatchDeposit(10, first))
                batchTill.tell(BatchDeposit(10, first))
                batchTill.tell(BatchDeposit(5, first.copy(sequence = 2)))
                opened.countDown()
                // The confirmations are tells to another actor, and a close drops what it has not handled yet.
                batchTill.ask(1.minutes) { BatchCount(it) }.also { awaitIdle() }
            }
            counted shouldBe Either.Right(15)
            journal.events(batchTillId, batchPence) shouldContainExactly listOf(10, 5)
            withClue("every delivery is confirmed, the duplicate included") { confirmed.size shouldBe 3 }
        }

        test("a batch that crosses a snapshot's multiple saves one") {
            val journal = AppendCounting()
            val snapshots = InMemorySnapshots()
            val opened = CountDownLatch(1)
            val _ = scope {
                journal(journal)
                snapshots(snapshots)
                val batchTill = spawn("batchTill", batchTill(batch = 64, opened, every = 10))
                (1..25).forEach { batchTill.tell(BatchRing(1)) }
                opened.countDown()
                awaitIdle()
            }
            snapshots.latest(batchTillId)?.let { batchTotal.decode(it.bytes) } shouldBe 25
        }

        test("a command that stops ends the batch, and what came after it is not written") {
            val journal = AppendCounting()
            val opened = CountDownLatch(1)
            val letters = CopyOnWriteArrayList<DeadLetter>()
            val _ = scope {
                journal(journal)
                onDeadLetter(letters::add)
                val batchTill = spawn("batchTill", batchTill(batch = 64, opened))
                batchTill.tell(BatchRing(1))
                batchTill.tell(BatchLook)
                batchTill.tell(BatchRing(2))
                batchTill.tell(BatchClose)
                batchTill.tell(BatchRing(3))
                opened.countDown()
                watch(batchTill).await()
            }
            val dead = listOf(BatchLook to DeadLetter.Why.Unhandled, BatchRing(3) to DeadLetter.Why.Stopped)
            journal.events(batchTillId, batchPence) shouldContainExactly listOf(1, 2)
            letters.map { it.message to it.why } shouldContainExactly dead
        }

        test("a conflicted batch is lost as a failed step is, and the actor recovers to what the journal holds") {
            val journal = AppendCounting(failing = setOf(1))
            val opened = CountDownLatch(1)
            val counted = scope {
                journal(journal)
                val batchTill = spawn("batchTill", batchTill(batch = 64, opened), restart = Schedule.recurs(1))
                (1..3).forEach { batchTill.tell(BatchRing(it)) }
                opened.countDown()
                awaitIdle()
                batchTill.tell(BatchRing(10))
                batchTill.ask(1.minutes) { BatchCount(it) }
            }
            counted shouldBe Either.Right(10)
            journal.events(batchTillId, batchPence) shouldContainExactly listOf(10)
        }
    }
}
