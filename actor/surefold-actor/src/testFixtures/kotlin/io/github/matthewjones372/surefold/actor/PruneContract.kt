package io.github.matthewjones372.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldContainExactly
import dev.surefold.fail.left
import dev.surefold.fail.right
import org.junit.jupiter.api.Test

/**
 * What every [JournalPruning] does, as tests a journal's own test class inherits: it answers [journal], an empty
 * journal that prunes. A journal that is also a [JournalFeed] is held to its feed losing what was deleted.
 */
abstract class PruneContract<J> where J : Journal, J : JournalPruning {

    /** A journal with no events in it that prunes, fresh for each test. */
    abstract fun journal(): J

    private val sam = PersistenceId("diary", "sam")

    private fun J.filled(count: Int, id: PersistenceId = sam): J = also {
        append(id, 0, (1..count).map { n -> "e$n".toByteArray() })
    }

    private fun J.sequences(id: PersistenceId = sam, from: Long = 1) = read(id, from).map { it.sequence }

    @Test
    fun `after a deletion the journal answers the events kept, from wherever it is asked`() {
        val journal = journal().filled(5)

        journal.deleteTo(sam, 3)

        journal.sequences() shouldContainExactly listOf(4L, 5L)
        journal.sequences(from = 5) shouldContainExactly listOf(5L)
        journal.read(sam).map { String(it.bytes) } shouldContainExactly listOf("e4", "e5")
    }

    @Test
    fun `the newest event is never deleted, and appends go on after it`() {
        val journal = journal().filled(5)

        journal.deleteTo(sam, 10)

        journal.sequences() shouldContainExactly listOf(5L)
        journal.append(sam, 5, listOf("e6".toByteArray())) shouldBe 6L.right()
        journal.append(sam, 3, listOf("stale".toByteArray())) shouldBe JournalConflict(sam, 3, 6).left()
        journal.sequences() shouldContainExactly listOf(5L, 6L)
    }

    @Test
    fun `a deletion touches only its own id, and one for an id with no events does nothing`() {
        val kim = PersistenceId("diary", "kim")
        val journal = journal().filled(3).filled(3, kim)

        journal.deleteTo(sam, 2)
        journal.deleteTo(PersistenceId("diary", "nobody"), 5)

        journal.sequences() shouldContainExactly listOf(3L)
        journal.sequences(kim) shouldContainExactly listOf(1L, 2L, 3L)
    }

    @Test
    fun `a feed no longer answers what was deleted`() {
        val journal = journal().filled(4)
        val feed = journal as? JournalFeed ?: return

        journal.deleteTo(sam, 2)

        feed.after("diary", 0, 10).map { it.sequence } shouldContainExactly listOf(3L, 4L)
    }

    @Test
    fun `a deletion bounded by a feed offset keeps every event after it`() {
        val kim = PersistenceId("diary", "kim")
        val journal = journal()
        val feed = journal as? JournalFeed ?: return
        for (n in 1..5) {
            journal.append(sam, n - 1L, listOf("s$n".toByteArray()))
            journal.append(kim, n - 1L, listOf("k$n".toByteArray()))
        }
        val samSecond = feed.after("diary", 0, 100).single { it.id == sam && it.sequence == 2L }.offset

        journal.deleteTo(sam, 4, readTo = samSecond)
        journal.deleteTo(kim, 4, readTo = 0)

        journal.sequences() shouldContainExactly listOf(3L, 4L, 5L)
        journal.sequences(kim) shouldContainExactly listOf(1L, 2L, 3L, 4L, 5L)
    }
}
