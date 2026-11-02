package io.github.matthewjones372.surefold.actor.journal.jdbc

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.JournalConflict
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Prune
import io.github.matthewjones372.surefold.actor.ShardedJournal
import io.github.matthewjones372.surefold.actor.ShardedSnapshots
import io.github.matthewjones372.surefold.actor.StateCodec
import io.github.matthewjones372.surefold.actor.awaitIdle
import io.github.matthewjones372.surefold.actor.events
import io.github.matthewjones372.surefold.actor.every
import io.github.matthewjones372.surefold.actor.journal
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.snapshots
import io.github.matthewjones372.surefold.actor.spawn

private val shardedCents = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = "$event".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val shardedSum = object : StateCodec<Int> {
    override fun encode(state: Int): ByteArray = "$state".toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

/** Two fresh databases, and one journal over both, for one test. */
private class TwoDatabases {
    val a = Postgres.fresh()
    val b = Postgres.fresh()
    val journal = ShardedJournal(listOf("db-a" to JdbcJournal(a), "db-b" to JdbcJournal(b)))
}

private fun till(id: PersistenceId, prune: Prune = Prune.never, batch: Int = 64) = persistent<Int, Int, Int>(
    id = id,
    empty = 0,
    codec = shardedCents,
    command = { _, _, paid -> persist(paid) },
    event = { sum, paid -> sum + paid },
    snapshots = every(10, shardedSum, prune),
    batch = batch,
)

/** Spec 0088 on Postgres: two databases, each with its own `ordering`, behind one journal. */
object ShardedPostgresSuite : Suite() {
    init {
        test("entities spread over two databases, and each database's feed holds its own ids' events once") {
            with(TwoDatabases()) {
                val ids = (1..200).map { PersistenceId("till", "t-$it") }
                scope {
                    journal(journal)
                    val tills = ids.map { spawn(it.id, till(it)) }
                    (1..3).forEach { paid -> tills.forEach { it.tell(paid) } }
                    awaitIdle()
                }

                val fed = journal.feeds.associate { (database, feed) -> database to feed.after("till", 0, 10_000) }
                ids.forAll { id -> journal.events(id, shardedCents) shouldContainExactly listOf(1, 2, 3) }
                fed.values.flatten().map { it.id to it.sequence } shouldContainExactlyInAnyOrder
                    ids.flatMap { id -> (1L..3L).map { id to it } }
                fed.entries.forAll { (database, events) ->
                    events.all { journal.database(it.id) == database } shouldBe true
                }
                fed.values.forAll { events -> events.isNotEmpty() shouldBe true }
            }
        }

        test("a conflict on Postgres is still a conflict") {
            with(TwoDatabases()) {
                val id = PersistenceId("till", "t-1")
                journal.append(id, 0, listOf("1".toByteArray()))
                journal.append(id, 0, listOf("2".toByteArray())) shouldBe Either.Left(JournalConflict(id, 0, 1))
            }
        }

        test("snapshots sit beside their events, and each database prunes on its own read models' offsets") {
            with(TwoDatabases()) {
                val offsets = JdbcOffsets(a)
                val ids = (1..50).map { PersistenceId("till", "t-$it") }
                val inA = ids.first { journal.database(it) == "db-a" }
                val inB = ids.first { journal.database(it) == "db-b" }
                offsets.save(ShardedJournal.progress("totals", "db-a"), Long.MAX_VALUE)
                val prune = Prune.after(offsets, journal, "totals")

                scope {
                    journal(journal)
                    snapshots(ShardedSnapshots(listOf("db-a" to JdbcSnapshots(a), "db-b" to JdbcSnapshots(b))))
                    // One payment at a time, so the snapshots fall at 10 and 20 whatever the timing.
                    val tills = listOf(spawn("a", till(inA, prune, batch = 1)), spawn("b", till(inB, prune, batch = 1)))
                    (1..25).forEach { paid -> tills.forEach { it.tell(paid) } }
                    awaitIdle()
                }

                JdbcSnapshots(a).latest(inA)?.sequence shouldBe 20
                JdbcSnapshots(b).latest(inB)?.sequence shouldBe 20
                journal.read(inA).first().sequence shouldBe 11
                journal.read(inB).first().sequence shouldBe 1
            }
        }
    }
}
