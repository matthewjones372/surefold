package dev.surefold.actor

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldThrow

private val ledgerOf = PersistenceId("ledger", "l-1")

private val entry =
    object : EventCodec<Int> {
        override fun encode(event: Int): ByteArray = event.toString().toByteArray()

        override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
    }

private val sum =
    object : StateCodec<Long> {
        override fun encode(state: Long): ByteArray = state.toString().toByteArray()

        override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
    }

/** Adds [amount] to the ledger, as one event. */
private data class Post(val amount: Int)

private fun ledger(prune: Prune) =
    persistent<Post, Int, Long>(
        id = ledgerOf,
        empty = 0,
        codec = entry,
        snapshots = every(100, sum, prune = prune),
        command = { _, _, post -> persist(post.amount) },
        event = { total, amount -> total + amount },
    )

private fun Journal.kept() = read(ledgerOf).map { it.sequence }

object PruneSuite : Suite() {
    init {
        test("with pruning, 1,050 events keep only 901 to 1,050, and a restart reaches the same state") {
            val journal = InMemoryJournal()
            testActors(journal = journal) {
                val book = spawn("ledger", ledger(prune = Prune.always))
                (1..1_050).forEach { book.send(Post(it)) }
                val before = book.state

                journal.kept() shouldBe (901L..1_050L).toList()
                book.restart()

                book.state shouldBe before
            }
        }

        test("without pruning, every event is kept") {
            val journal = InMemoryJournal()
            testActors(journal = journal) {
                val book = spawn("ledger", ledger(prune = Prune.never))
                (1..250).forEach { book.send(Post(it)) }

                journal.kept() shouldBe (1L..250L).toList()
            }
        }

        test("a snapshot that fails to save deletes nothing") {
            val journal = InMemoryJournal()
            val broken =
                object : SnapshotStore {
                    override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) =
                        error("the snapshot store is down")

                    override fun latest(id: PersistenceId): Snapshot? = null
                }
            testActors(journal = journal, snapshots = broken) {
                val book = spawn("ledger", ledger(prune = Prune.always))
                (1..250).forEach { book.send(Post(it)) }

                journal.kept() shouldBe (1L..250L).toList()
            }
        }

        test("a start that finds a pruned history and no snapshot fails, and names the gap") {
            val journal = InMemoryJournal()
            testActors(journal = journal) {
                val book = spawn("ledger", ledger(prune = Prune.always))
                (1..250).forEach { book.send(Post(it)) }
            }

            testActors(journal = journal, snapshots = InMemorySnapshots()) {
                shouldThrow<IllegalStateException>({ spawn("ledger", ledger(prune = Prune.always)) }) { failed ->
                    failed.message shouldContain "events 1 to 100 were deleted, and no snapshot covers them"
                }
            }
        }

        test("pruning after a read model deletes no further than it has read, and more once it catches up") {
            val journal = InMemoryJournal()
            val offsets = InMemoryOffsets()
            testActors(journal = journal) {
                val book = spawn("ledger", ledger(prune = Prune.after(offsets, "totals", "search")))
                offsets.save("totals", 150)
                offsets.save("search", 900)
                (1..350).forEach { book.send(Post(it)) }

                journal.kept() shouldBe (151L..350L).toList()

                offsets.save("totals", 900)
                (351..450).forEach { book.send(Post(it)) }

                journal.kept() shouldBe (301L..450L).toList()
            }
        }

        test("a read model with no offset saved holds pruning back entirely") {
            val journal = InMemoryJournal()
            val offsets = InMemoryOffsets().also { it.save("totals", 10_000) }
            testActors(journal = journal) {
                val book = spawn("ledger", ledger(prune = Prune.after(offsets, "totals", "never-ran")))
                (1..350).forEach { book.send(Post(it)) }

                journal.kept() shouldBe (1L..350L).toList()
            }
        }
    }
}
