package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.Journal
import dev.surefold.actor.JournalContract

class GroupCommitJournalTest : JournalContract() {
    override fun journal(): Journal = JdbcJournal(Postgres.fresh(), groupCommit = GroupCommit())
}
