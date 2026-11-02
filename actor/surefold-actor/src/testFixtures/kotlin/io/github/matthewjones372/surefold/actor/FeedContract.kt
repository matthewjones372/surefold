package io.github.matthewjones372.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.withClue
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * What every [JournalFeed] does, as tests a feed's own test class inherits: it answers [journal], an empty journal
 * that is its own feed, and runs these unchanged.
 */
abstract class FeedContract<J> where J : Journal, J : JournalFeed {

    /** A journal with no events in it that is also its feed, fresh for each test. */
    abstract fun journal(): J

    private fun J.put(kind: String, id: String, vararg texts: String) {
        val of = PersistenceId(kind, id)
        append(of, read(of).size.toLong(), texts.map { it.toByteArray() })
    }

    private fun List<FeedEvent>.seen() = map { "${it.id.id}#${it.sequence}:${String(it.bytes)}" }

    @Test
    fun `a feed answers every event of its kind, across ids, in the order they were appended`() {
        val journal = journal()
        journal.put("order", "o-1", "placed")
        journal.put("basket", "b-1", "filled")
        journal.put("order", "o-2", "placed")
        journal.put("order", "o-1", "paid", "shipped")

        val orders = journal.after("order", 0, 10)

        orders.seen() shouldContainExactly listOf("o-1#1:placed", "o-2#1:placed", "o-1#2:paid", "o-1#3:shipped")
        withClue("offsets grow") { orders.map { it.offset }.zipWithNext().all { (a, b) -> a < b } shouldBe true }
        journal.after("basket", 0, 10).seen() shouldContainExactly listOf("b-1#1:filled")
        journal.after("nothing", 0, 10).shouldBeEmpty()
    }

    @Test
    fun `a read starts after the offset it is given, and answers at most its limit`() {
        val journal = journal()
        for (n in 1..5) journal.put("order", "o-$n", "placed")
        val all = journal.after("order", 0, 10)

        journal.after("order", all[1].offset, 2).seen() shouldContainExactly listOf("o-3#1:placed", "o-4#1:placed")
        journal.after("order", all.last().offset, 10).shouldBeEmpty()
    }

    @Test
    fun `an append that conflicts puts nothing in the feed`() {
        val journal = journal()
        journal.put("order", "o-1", "placed")

        journal.append(PersistenceId("order", "o-1"), 0, listOf("again".toByteArray()))

        journal.after("order", 0, 10).seen() shouldContainExactly listOf("o-1#1:placed")
    }

    @Test
    fun `a follower reading while many ids are appended to sees every event once, in growing offsets`() {
        val journal = journal()
        val writers = 8
        val each = 25
        val done = AtomicBoolean(false)
        val followed = mutableListOf<FeedEvent>()

        Executors.newVirtualThreadPerTaskExecutor().use { threads ->
            val follower = threads.submit {
                var last = 0L
                // Reads until the writers are done and one more read after that finds nothing new.
                while (true) {
                    val finished = done.get()
                    val read = journal.after("order", last, 64)
                    followed += read
                    last = read.lastOrNull()?.offset ?: last
                    if (finished && read.isEmpty()) break
                }
            }
            val appends = (1..writers).map { writer ->
                threads.submit { for (n in 1..each) journal.put("order", "o-$writer", "e$n") }
            }
            appends.forEach { it.get() }
            done.set(true)
            follower.get()
        }

        followed.size shouldBe writers * each
        followed.map { it.id.id to it.sequence }.toSet().size shouldBe writers * each
        withClue("offsets grow") { followed.map { it.offset }.zipWithNext().all { (a, b) -> a < b } shouldBe true }
        for (writer in 1..writers) {
            followed.filter { it.id.id == "o-$writer" }.map { it.sequence } shouldContainExactly (1L..each).toList()
        }
    }

    @Test
    fun `the feed keeps its own copy of the bytes`() {
        val journal = journal()
        journal.put("order", "o-1", "placed")

        journal.after("order", 0, 1).single().bytes[0] = 'x'.code.toByte()

        journal.after("order", 0, 1).seen() shouldContainExactly listOf("o-1#1:placed")
    }
}
