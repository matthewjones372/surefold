package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.FeedContract
import dev.surefold.actor.PruneContract

class JdbcFeedTest : FeedContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}

class JdbcPruneTest : PruneContract<JdbcJournal>() {
    override fun journal() = JdbcJournal(Postgres.fresh())
}
