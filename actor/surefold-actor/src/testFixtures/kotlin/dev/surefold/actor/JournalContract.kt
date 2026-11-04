package dev.surefold.actor

import dev.surefold.assert.shouldBe
import dev.surefold.assert.shouldBeEmpty
import dev.surefold.assert.shouldContainExactly
import dev.surefold.assert.withClue
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/**
 * What every [Journal] does, as tests a journal's own test class inherits: it answers [journal], a journal with
 * nothing in it, and runs these unchanged. A service with a journal of its own runs them the same way.
 */
abstract class JournalContract {

    /** A journal with no events in it, fresh for each test. */
    abstract fun journal(): Journal

    private val sam = PersistenceId("diary", "sam")

    private fun bytes(text: String) = text.toByteArray()

    private fun Journal.texts(id: PersistenceId, from: Long = 1) =
        read(id, from).map { it.sequence to String(it.bytes) }

    @Test
    fun `a read answers what was appended, in order, from where it is asked to start`() {
        val journal = journal()

        journal.append(sam, 0, listOf(bytes("one"), bytes("two"))) shouldBe 2L.right()
        journal.append(sam, 2, listOf(bytes("three"))) shouldBe 3L.right()

        journal.texts(sam) shouldContainExactly listOf(1L to "one", 2L to "two", 3L to "three")
        journal.texts(sam, from = 3) shouldContainExactly listOf(3L to "three")
        journal.texts(sam, from = 4).shouldBeEmpty()
        journal.read(PersistenceId("diary", "nobody")).shouldBeEmpty()
    }

    @Test
    fun `an append that expects the wrong sequence number is a conflict, and writes nothing`() {
        val journal = journal()
        journal.append(sam, 0, listOf(bytes("one")))

        journal.append(sam, 0, listOf(bytes("again"), bytes("and again"))) shouldBe JournalConflict(sam, 0, 1).left()
        journal.append(sam, 5, listOf(bytes("ahead"))) shouldBe JournalConflict(sam, 5, 1).left()

        journal.texts(sam) shouldContainExactly listOf(1L to "one")
    }

    @Test
    fun `each id has its own sequence, whether the kind or the id differs`() {
        val journal = journal()
        val ids = listOf(sam, PersistenceId("diary", "kim"), PersistenceId("letters", "sam"))

        ids.forEach { id -> journal.append(id, 0, listOf(bytes("${id.kind}/${id.id}"))) shouldBe 1L.right() }

        ids.forEach { id -> journal.texts(id) shouldContainExactly listOf(1L to "${id.kind}/${id.id}") }
    }

    @Test
    fun `the journal keeps its own copy of the bytes`() {
        val journal = journal()
        val written = bytes("one")
        journal.append(sam, 0, listOf(written))

        written[0] = 'x'.code.toByte()
        journal.read(sam).single().bytes[0] = 'y'.code.toByte()

        journal.texts(sam) shouldContainExactly listOf(1L to "one")
    }

    @Test
    fun `of two writers racing to append after the same event, exactly one succeeds`() {
        val journal = journal()
        val rounds = 50

        Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("journal-contract-writer-", 0).factory())
            .use { writers ->
                for (last in 0 until rounds.toLong()) {
                    val start = CountDownLatch(1)
                    val answers = List(2) { writer ->
                        writers.submit<Either<JournalConflict, Long>> {
                            start.await()
                            journal.append(sam, last, listOf(bytes("$last by $writer")))
                        }
                    }
                    start.countDown()
                    val won = answers.map { it.get() }
                    withClue("after event $last, the two writers answered $won") {
                        won.count { it == (last + 1).right() } shouldBe 1
                        won.count { it == JournalConflict(sam, last, last + 1).left() } shouldBe 1
                    }
                }
            }

        journal.read(sam).map { it.sequence } shouldContainExactly (1..rounds.toLong()).toList()
    }
}
