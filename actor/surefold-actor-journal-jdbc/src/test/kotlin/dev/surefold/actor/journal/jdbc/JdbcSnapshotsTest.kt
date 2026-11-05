package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.SnapshotContract
import dev.surefold.actor.SnapshotStore

class JdbcSnapshotsTest : SnapshotContract() {
    override fun store(): SnapshotStore =
        JdbcSnapshots(
            Postgres.fresh(),
        )
}
