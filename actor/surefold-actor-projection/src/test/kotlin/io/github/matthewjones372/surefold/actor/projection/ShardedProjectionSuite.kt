package io.github.matthewjones372.surefold.actor.projection

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.InMemoryJournal
import io.github.matthewjones372.surefold.actor.InMemoryOffsets
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Prune
import io.github.matthewjones372.surefold.actor.ShardedJournal
import io.github.matthewjones372.surefold.actor.StateCodec
import io.github.matthewjones372.surefold.actor.every
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.testActors
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.start
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private val shardedAmount = object : EventCodec<Int> {
    override fun encode(event: Int): ByteArray = event.toString().toByteArray()

    override fun decode(bytes: ByteArray): Int = String(bytes).toInt()
}

private val shardedTotal = object : StateCodec<Long> {
    override fun encode(state: Long): ByteArray = state.toString().toByteArray()

    override fun decode(bytes: ByteArray): Long = String(bytes).toLong()
}

/** Pays [amount] into a till, as one event. */
private data class Pay(val amount: Int)

private fun till(id: PersistenceId, prune: Prune) = persistent<Pay, Int, Long>(
    id = id,
    empty = 0,
    codec = shardedAmount,
    snapshots = every(100, shardedTotal, prune = prune),
    command = { _, _, pay -> persist(pay.amount) },
    event = { sum, paid -> sum + paid },
)

/** A journal over two databases, and its read models' offsets, for one test. */
private class TwoDatabases {
    val journal = ShardedJournal(listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal()))
    val offsets = InMemoryOffsets()
}

/** Spec 0088: a read model over a journal across databases is one projection per database. */
object ShardedProjectionSuite : Suite() {
    init {
        test("one projection per database sees every event once, and only its own database's") {
            with(TwoDatabases()) {
                val ids = (1..200).map { PersistenceId("till", "t-$it") }
                ids.forEach { id -> journal.append(id, 0, (1..5).map { "$it".toByteArray() }) }

                val seen = ConcurrentHashMap<String, MutableList<Pair<PersistenceId, Long>>>()
                val all = CountDownLatch(ids.size * 5)
                val runs = journal.feeds.map { (database, feed) ->
                    Projection.follow(feed, "till", shardedAmount, offsets, ShardedJournal.progress("totals", database))
                        .mapFollowed { event ->
                            seen.computeIfAbsent(database) { Collections.synchronizedList(mutableListOf()) }
                                .add(event.id to event.sequence)
                            all.countDown()
                        }
                        .runProjecting()
                        .start(Forks())
                }
                all.await(1, TimeUnit.MINUTES) shouldBe true
                runs.forEach { it.close() }

                seen.values.flatten() shouldContainExactlyInAnyOrder
                    ids.flatMap { id -> (1L..5L).map { id to it } }
                seen.entries.forAll { (database, events) ->
                    events.all { (id, _) -> journal.database(id) == database } shouldBe true
                }
            }
        }

        test("pruning in one database waits only on what has read that database") {
            with(TwoDatabases()) {
                val ids = (1..50).map { PersistenceId("till", "t-$it") }
                val inA = ids.first { journal.database(it) == "db-a" }
                val inB = ids.first { journal.database(it) == "db-b" }
                // Everything in db-a has been read; nothing in db-b has.
                offsets.save(ShardedJournal.progress("totals", "db-a"), Long.MAX_VALUE)

                val prune = Prune.after(offsets, journal, "totals")
                testActors(journal = journal) {
                    val a = spawn("a", till(inA, prune))
                    val b = spawn("b", till(inB, prune))
                    (1..250).forEach {
                        a.send(Pay(it))
                        b.send(Pay(it))
                    }
                }

                journal.read(inA).first().sequence shouldBe 101
                journal.read(inB).first().sequence shouldBe 1
            }
        }
    }
}
