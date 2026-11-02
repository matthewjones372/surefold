package io.github.matthewjones372.surefold.actor.journal.jdbc

import dev.surefold.TestClock
import dev.surefold.clock
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldContainExactlyInAnyOrder
import io.github.matthewjones372.surefold.actor.FeedEvent
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Slices
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

private fun List<FeedEvent>.ids() = map { "${it.id.id}#${it.sequence}" }

/** An append that has taken its place in the feed and not yet committed, on a connection of its own. */
private fun DataSource.pending(id: String): Connection = connection.apply {
    autoCommit = false
    prepareStatement("insert into surefold_journal (kind, id, seq_nr, bytes, slice) values ('order', ?, 1, ?, ?)").use {
        it.setString(1, id)
        it.setBytes(2, "pending".toByteArray())
        it.setInt(3, Slices.of(PersistenceId("order", id)))
        it.executeUpdate()
    }
}

private fun JdbcJournal.put(id: String) = append(PersistenceId("order", id), 0, listOf("placed".toByteArray()))

/**
 * What the JDBC feed does at a gap on Postgres: an append still open on one connection holds the feed back on another,
 * and one that never commits is passed after `gapTimeout`.
 *
 * One test at a time: a gap is read from `pg_current_snapshot()`, whose horizon covers every open transaction on the
 * server, in every database. These tests hold transactions open on purpose, so another test's would read as a gap here.
 */
object PostgresFeedGapSuite : Suite(concurrent = false) {
    /** An empty database with the journal's table, fresh for each test. */
    private fun source(): DataSource = Postgres.fresh()

    init {
        test("an append still open holds back what committed after it, until it commits") {
            val source = source()
            val journal = JdbcJournal(source)
            journal.put("o-1")
            val open = source.pending("o-2")
            journal.put("o-3")

            journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

            open.use { it.commit() }
            journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-2#1", "o-3#1")
        }

        test("a gap that does not fill within gapTimeout is passed, and what is after it is read") {
            val moving = TestClock()
            clock.locally(moving) {
                val source = source()
                val journal = JdbcJournal(source, gapTimeout = 10.seconds)
                journal.put("o-1")
                source.pending("o-2").use { open ->
                    journal.put("o-3")
                    journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                    moving.adjust(9.seconds)
                    journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                    open.rollback()
                    moving.adjust(1.seconds)
                    journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-3#1")
                }
            }
        }

        test("gaps seen together are passed together, after one gapTimeout and not one each") {
            val moving = TestClock()
            clock.locally(moving) {
                val source = source()
                val journal = JdbcJournal(source, gapTimeout = 10.seconds)
                journal.put("o-0")
                // Five appends that each took a place in the feed and rolled back, as a database stopping mid-write
                // does.
                (1..5).forEach { n ->
                    source.pending("gone-$n").use { open -> journal.put("o-$n").also { open.rollback() } }
                }
                journal.after("order", 0, 20).ids() shouldContainExactly listOf("o-0#1")

                moving.adjust(10.seconds)
                journal.after("order", 0, 20).ids() shouldContainExactly (0..5).map { "o-$it#1" }
            }
        }

        test(
            "partitions by slice read every event once between them, and none reads past a gap in another's slices",
        ) {
            val source = source()
            val journal = JdbcJournal(source)
            (1..100).forEach { journal.put("o-$it") }
            val open = source.pending("gap")
            (101..200).forEach { journal.put("o-$it") }
            val quarters = (0 until 4).map { k -> k * Slices.COUNT / 4 until (k + 1) * Slices.COUNT / 4 }

            val before = quarters.flatMap { journal.after("order", it, 0, 1_000) }
            before.ids() shouldContainExactlyInAnyOrder (1..100).map { "o-$it#1" }

            open.use { it.commit() }
            val all = quarters.map { journal.after("order", it, 0, 1_000) }
            all.flatten().ids() shouldContainExactlyInAnyOrder (1..200).map { "o-$it#1" } + "gap#1"
            all.withIndex().forAll { (k, events) ->
                events.all { Slices.of(it.id) in quarters[k] } shouldBe true
                events.map { it.offset } shouldBe events.map { it.offset }.sorted()
            }
        }

        test("a gap above everything read holds nothing back") {
            val source = source()
            val journal = JdbcJournal(source)
            journal.put("o-1")

            source.pending("o-2").use { journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1") }
        }

        test("an append whose writer is slow to commit is waited for past gapTimeout, and read once it commits") {
            val moving = TestClock()
            clock.locally(moving) {
                val source = source()
                val journal = JdbcJournal(source, gapTimeout = 10.seconds)
                journal.put("o-1")
                source.pending("o-2").use { open ->
                    journal.put("o-3")
                    journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                    // A writer frozen mid-commit, as a paused JVM is: its transaction is still running, so the gap
                    // holds.
                    moving.adjust(30.seconds)
                    journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")

                    open.commit()
                    journal.after("order", 0, 10).ids() shouldContainExactly
                        listOf("o-1#1", "o-2#1", "o-3#1")
                }
            }
        }

        test("a rolled back append's gap is passed once its transaction has ended, well inside gapTimeout") {
            val moving = TestClock()
            clock.locally(moving) {
                val source = source()
                val journal = JdbcJournal(source, gapTimeout = 10.seconds)
                journal.put("o-1")
                source.pending("o-2").use { open ->
                    journal.put("o-3")
                    (journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1")).also { open.rollback() }
                }
                moving.adjust(2.seconds)
                journal.after("order", 0, 10).ids() shouldContainExactly listOf("o-1#1", "o-3#1")
            }
        }
    }
}
