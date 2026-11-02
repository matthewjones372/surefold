package io.github.matthewjones372.surefold.actor.journal.jdbc

import dev.surefold.assert.shouldContain
import dev.surefold.assert.shouldThrow
import io.github.matthewjones372.surefold.actor.Journal
import io.github.matthewjones372.surefold.actor.JournalContract
import io.github.matthewjones372.surefold.actor.PersistenceId
import org.junit.jupiter.api.Test
import java.sql.SQLException

class JdbcJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh())

    @Test
    fun `a database that cannot answer throws from the append and the read, as a journal that cannot write does`() {
        val journal = JdbcJournal(Postgres.empty())
        val sam = PersistenceId("diary", "sam")

        shouldThrow<SQLException> { journal.append(sam, 0, listOf(byteArrayOf(1))) }.message shouldContain
            "surefold_journal"
        shouldThrow<SQLException> { journal.read(sam) }
    }
}
