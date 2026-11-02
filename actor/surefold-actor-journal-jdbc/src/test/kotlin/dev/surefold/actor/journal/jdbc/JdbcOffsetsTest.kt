package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.OffsetContract
import dev.surefold.actor.OffsetStore

class JdbcOffsetsTest : OffsetContract() {
    override fun store(): OffsetStore = JdbcOffsets(Postgres.fresh())
}
