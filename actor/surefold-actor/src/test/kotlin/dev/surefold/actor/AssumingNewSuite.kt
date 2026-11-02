package dev.surefold.actor

import dev.surefold.fail.Either
import dev.surefold.fail.Fail
import dev.surefold.fail.right
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.scope
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.minutes

private val tallyCodec = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private sealed interface GuessCommand

private data class Plus(val n: Int) : GuessCommand

private data class Sum(val reply: Reply<Int>) : GuessCommand

/** Persists nothing, and writes the total it was decided against to the log. */
private data object Glance : GuessCommand

private fun tallyOf(id: String) = PersistenceId("tally", id)

/**
 * A running total remembered by its events, started with [start] and deciding up to [batch] commands per append. Each
 * `then` and each start hook is written to [log], the hook with the total its start left. A start hook waits for
 * [gate], so the commands told meanwhile are waiting together when the first turn comes.
 */
private fun tally(
    id: String,
    start: Start,
    log: ConcurrentLinkedQueue<String>,
    batch: Int = 1,
    gate: CountDownLatch? = null,
): Behaviour<GuessCommand, Remembered<Int>, JournalConflict> {
    val persistent = persistent<GuessCommand, Int, Int>(
        id = tallyOf(id),
        empty = 0,
        codec = tallyCodec,
        command = { _, total, command ->
            when (command) {
                is Plus -> persist(command.n).then { after -> log += "added ${command.n} to make $after" }
                is Sum -> none().then { command.reply(total) }
                Glance -> none().then { log += "saw $total" }
            }
        },
        event = Int::plus,
        batch = batch,
        start = start,
    )
    val started: Fail<JournalConflict>.(Ctx<GuessCommand>, Remembered<Int>) -> Next<Remembered<Int>> = { ctx, state ->
        gate?.await()
        val next = persistent.start?.invoke(this, ctx, state) ?: Next.Stay
        log += "started at ${((next as? Next.Become)?.state ?: state).value}"
        next
    }
    return Behaviour(
        initial = persistent.initial,
        turn = persistent.turn,
        signal = persistent.signal,
        start = started,
        turns = persistent.turns,
        batch = persistent.batch,
    )
}

/** A journal that counts its reads and its appends. */
private class ReadCounting(private val inner: Journal = InMemoryJournal()) : Journal by inner {
    val reads = AtomicInteger()
    val appends = AtomicInteger()

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> =
        inner.append(id, expected, events).also { appends.incrementAndGet() }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = inner.read(id, from).also {
        reads.incrementAndGet()
    }
}

/** Spec 0198: a persistent actor started assuming it is new reads its journal only when that guess is wrong. */
object AssumingNewSuite : Suite() {
    init {
        for (batch in listOf(1, 8)) batched(batch)
        runs()
    }

    /** The first entry's tests, with [batch] commands decided per append. */
    private fun batched(batch: Int) {
        test(
            "a new entity's first command persists without a read, " +
                "and its start hooks see the empty state, batch $batch",
        ) {
            val journal = ReadCounting()
            val log = ConcurrentLinkedQueue<String>()
            val total = testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.assumingNew, log, batch))
                ada.send(Plus(5))
                ada.send(Plus(2))
                ada.ask { Sum(it) }
            }

            total shouldBe 7.right()
            journal.reads.get() shouldBe 0
            log.toList() shouldContainExactly listOf("started at 0", "added 5 to make 5", "added 2 to make 7")
        }

        test(
            "an entity that existed recovers once its first append conflicts, " +
                "and decides that command again, batch $batch",
        ) {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val (total, restarts) = testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.assumingNew, log, batch))
                ada.send(Plus(2))
                ada.ask { Sum(it) } to ada.restarts
            }

            // The guess's `then` never ran; the hooks ran again with what was recovered; supervision was not asked.
            total shouldBe 42.right()
            restarts shouldBe 0
            journal.events(tallyOf("ada"), tallyCodec) shouldContainExactly listOf(40, 2)
            log.toList() shouldContainExactly listOf("started at 0", "started at 40", "added 2 to make 42")
        }

        test(
            "a first command that persists nothing is answered from the recovered state, " +
                "not the empty one, batch $batch",
        ) {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val total = testActors(journal = journal) {
                spawn("ada", tally("ada", Start.assumingNew, log, batch)).ask { Sum(it) }
            }

            total shouldBe 40.right()
            journal.reads.get() shouldBe 1
            log.toList() shouldContainExactly listOf("started at 0", "started at 40")
        }

        test("recovering, the default, reads the journal before the first command as before, batch $batch") {
            val journal = ReadCounting()
            val log = ConcurrentLinkedQueue<String>()
            testActors(journal = journal) {
                val ada = spawn("ada", tally("ada", Start.recovering, log, batch))
                ada.send(Plus(5))
            }

            journal.reads.get() shouldBe 1
        }

        test("on threads, a new entity reads nothing, and one that existed ends where its events say, batch $batch") {
            val journal = ReadCounting()
            journal.append(tallyOf("old"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val totals = scope {
                journal(journal)
                val fresh = spawn("fresh", tally("fresh", Start.assumingNew, log, batch))
                val old = spawn("old", tally("old", Start.assumingNew, log, batch))
                fresh.tell(Plus(5))
                old.tell(Plus(2))
                listOf(fresh.ask(1.minutes) { Sum(it) }, old.ask(1.minutes) { Sum(it) })
            }

            totals shouldBe listOf(5.right(), 42.right())
            journal.reads.get() shouldBe 1
            journal.events(tallyOf("old"), tallyCodec) shouldContainExactly listOf(40, 2)
        }
    }

    /** Commands waiting together when a lazy start comes, decided as one run on threads (spec 0198, entry 2). */
    private fun runs() {
        test("on threads, a run of commands to a new entity is one append and no read") {
            val journal = ReadCounting()
            val log = ConcurrentLinkedQueue<String>()
            val gate = CountDownLatch(1)
            val total = scope {
                journal(journal)
                val ada = spawn("ada", tally("ada", Start.assumingNew, log, batch = 8, gate = gate))
                ada.tell(Plus(5))
                ada.tell(Plus(2))
                gate.countDown()
                ada.ask(1.minutes) { Sum(it) }
            }

            total shouldBe 7.right()
            journal.reads.get() shouldBe 0
            journal.appends.get() shouldBe 1
            log.toList() shouldContainExactly listOf("started at 0", "added 5 to make 5", "added 2 to make 7")
        }

        test("on threads, a run to an entity that existed recovers once its append conflicts, and is decided again") {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val gate = CountDownLatch(1)
            val total = scope {
                journal(journal)
                val ada = spawn("ada", tally("ada", Start.assumingNew, log, batch = 8, gate = gate))
                ada.tell(Plus(2))
                ada.tell(Plus(3))
                gate.countDown()
                ada.ask(1.minutes) { Sum(it) }
            }

            // The guessed run's `then`s never ran, and both commands were decided again against 40, in order.
            total shouldBe 45.right()
            journal.reads.get() shouldBe 1
            journal.events(tallyOf("ada"), tallyCodec) shouldContainExactly listOf(40, 2, 3)
            log.toList() shouldContainExactly
                listOf("started at 0", "started at 40", "added 2 to make 42", "added 3 to make 45")
        }

        test("on threads, a run that persists nothing on a lazy start is decided against the recovered state") {
            val journal = ReadCounting()
            journal.append(tallyOf("ada"), 0, listOf(tallyCodec.encode(40)))
            val log = ConcurrentLinkedQueue<String>()
            val gate = CountDownLatch(1)
            val total = scope {
                journal(journal)
                val ada = spawn("ada", tally("ada", Start.assumingNew, log, batch = 8, gate = gate))
                ada.tell(Glance)
                ada.tell(Glance)
                gate.countDown()
                ada.ask(1.minutes) { Sum(it) }
            }

            total shouldBe 40.right()
            journal.reads.get() shouldBe 1
            log.toList() shouldContainExactly listOf("started at 0", "started at 40", "saw 40", "saw 40")
        }
    }
}
