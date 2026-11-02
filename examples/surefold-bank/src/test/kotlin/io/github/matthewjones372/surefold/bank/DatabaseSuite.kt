package io.github.matthewjones372.surefold.bank

import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.events
import io.github.matthewjones372.surefold.actor.journal.jdbc.JdbcJournal
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres

private val opened = PersistenceId("account", "a-1")

object DatabaseSuite : Suite() {
    init {
        test("a Postgres gets the journal's tables once, and keeps what is written to it") {
            val url = Postgres.emptyUrl()
            database(url).use { pool ->
                JdbcJournal(pool).append(opened, 0, listOf(AccountEvents.encode(AccountEvent.Opened(5))))
            }

            database(url).use { pool ->
                JdbcJournal(pool).events(opened, AccountEvents) shouldBe listOf(AccountEvent.Opened(5))
            }
        }
    }
}
