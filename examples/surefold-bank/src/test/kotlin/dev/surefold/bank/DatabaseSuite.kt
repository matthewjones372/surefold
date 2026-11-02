package dev.surefold.bank

import dev.surefold.actor.PersistenceId
import dev.surefold.actor.events
import dev.surefold.actor.journal.jdbc.JdbcJournal
import dev.surefold.actor.journal.jdbc.Postgres
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

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
