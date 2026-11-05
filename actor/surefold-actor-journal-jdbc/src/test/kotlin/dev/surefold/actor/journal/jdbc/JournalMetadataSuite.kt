package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.PersistenceId
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.logAnnotated

/** Spec 0123: an event is written with what its append carried, and read back with it. */
object JournalMetadataSuite : Suite() {
    private val order = PersistenceId("order", "o-1")
    private val placed = listOf("placed".toByteArray(), "paid".toByteArray())

    private fun carriedBy(journal: JdbcJournal) {
        logAnnotated("request_id" to "r-1", "quote" to "a \"quoted\" \\ value") { journal.append(order, 0, placed) }
        journal.append(PersistenceId("order", "o-2"), 0, listOf("placed".toByteArray()))

        val expected =
            mapOf(
                "surefold.annotation.request_id" to "r-1",
                "surefold.annotation.quote" to "a \"quoted\" \\ value",
            )
        journal.read(order).forAll { it.metadata shouldBe expected }
        journal.read(PersistenceId("order", "o-2")).single().metadata.entries.shouldBeEmpty()

        val feed = journal.after("order", 0, 10)
        feed.filter { it.id == order }.forAll { it.metadata shouldBe expected }
        feed.single { it.id.id == "o-2" }.metadata.entries.shouldBeEmpty()
        feed.size shouldBe 3
    }

    init {
        test(
            "an append made inside logAnnotated is read back, and fed, with its annotations, and one outside with none",
        ) {
            carriedBy(JdbcJournal(Postgres.fresh()))
        }

        test("the same holds for appends committed as a group") {
            carriedBy(JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit()))
        }
    }
}
