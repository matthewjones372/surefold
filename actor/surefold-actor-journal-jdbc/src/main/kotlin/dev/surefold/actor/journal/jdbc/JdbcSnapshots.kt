package dev.surefold.actor.journal.jdbc

import dev.surefold.actor.PersistenceId
import dev.surefold.actor.Snapshot
import dev.surefold.actor.SnapshotStore
import java.sql.Connection
import java.sql.SQLException
import javax.sql.DataSource

/**
 * A [SnapshotStore] in one `surefold_snapshot` table, reached through [dataSource], one row per id (spec 0074). Its
 * table is in the journal's changelog, `surefold/journal/jdbc/postgres.sql`.
 *
 * A save replaces the row only when it is newer, in one statement, so saves racing from two nodes leave the newest; a
 * first save for an id inserts, and one that loses that insert to another tries the replacement again.
 */
class JdbcSnapshots(private val dataSource: DataSource) : SnapshotStore {

    override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) =
        dataSource.connection.use { connection ->
            if (connection.replace(id, sequence, bytes) > 0) return@use
            try {
                connection.statement(
                    "insert into surefold_snapshot (kind, id, seq_nr, bytes) values (?, ?, ?, ?)",
                    id.kind,
                    id.id,
                    sequence,
                    bytes,
                ) {
                    it.executeUpdate()
                }
            } catch (taken: SQLException) {
                if (taken.sqlState != DUPLICATE_KEY) throw taken
                // Another save inserted first: the row is there now, and replacing it decides which is newer.
                connection.replace(id, sequence, bytes)
            }
        }

    override fun latest(id: PersistenceId): Snapshot? =
        dataSource.connection.use { connection ->
            connection.statement(
                "select seq_nr, bytes from surefold_snapshot where kind = ? and id = ?",
                id.kind,
                id.id,
            ) {
                it.executeQuery().use { rows -> if (rows.next()) Snapshot(rows.getLong(1), rows.getBytes(2)) else null }
            }
        }

    /** Replaces [id]'s row with the snapshot at [sequence] if the one held is older: how many rows it changed. */
    private fun Connection.replace(id: PersistenceId, sequence: Long, bytes: ByteArray): Int =
        statement(
            "update surefold_snapshot set seq_nr = ?, bytes = ? where kind = ? and id = ? and seq_nr < ?",
            sequence,
            bytes,
            id.kind,
            id.id,
            sequence,
        ) {
            it.executeUpdate()
        }
}
