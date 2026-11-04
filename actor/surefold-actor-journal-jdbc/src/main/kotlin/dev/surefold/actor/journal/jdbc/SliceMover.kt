package dev.surefold.actor.journal.jdbc

import dev.surefold.Schedule
import dev.surefold.actor.OffsetStore
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.ShardedJournal
import dev.surefold.actor.SliceMap
import dev.surefold.actor.Slices
import dev.surefold.clock
import dev.surefold.logInfo
import java.sql.Connection
import javax.sql.DataSource
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A range of [slices] given by [source] to [target] at [version]; [copiedTo] is the source's last ordering in it. */
data class SliceMove(
    val version: Long,
    val slices: IntRange,
    val source: String,
    val target: String,
    val copiedTo: Long,
)

/**
 * Moves ranges of slices between the named [databases] of one sharded journal (spec 0105); the first holds the slice
 * table. A move copies the range's events and snapshots while appends go on, fences the range in its source so that
 * appends to it are refused, waits for appends already running to end (at most [drainWithin]), copies what they
 * wrote, and bumps the table's version with the range on its target. Only the moving range pauses, and only from the
 * fence to the switch. The source keeps its rows until [cleanUp].
 *
 * On a database without transaction snapshots the wait for running appends is [settle].
 */
class SliceMover(
    private val databases: List<Pair<String, DataSource>>,
    private val drainWithin: Duration = 30.seconds,
    private val settle: Duration = 1.seconds,
) {
    private val primary = databases.first().second
    private val byName = databases.toMap()
    private val table = JdbcSliceTable(primary, databases.map { it.first }, refreshEvery = Duration.ZERO)
    private val time = clock.get()

    init {
        require(byName.size == databases.size) { "databases named twice: ${databases.map { it.first }}" }
    }

    /** Moves [slices], all owned by one database, to [target]. */
    @Suppress("TooGenericExceptionCaught") // rethrown, whatever it is, once the fence is lifted
    fun move(slices: IntRange, target: String): SliceMove {
        val map = table.refresh()
        val owners = slices.mapTo(LinkedHashSet(), map::owner)
        require(owners.size == 1) { "slices $slices are owned by ${owners.size} databases: $owners; move each range" }
        val sourceName = owners.single()
        require(sourceName != target) { "slices $slices are already on $target" }
        val source = database(sourceName)
        val into = database(target)
        val range = "$slices $sourceName->$target"
        step(range, "copy") { repeat(2) { copy(source, into, slices) } }
        val fencedOnTarget = into.connection.use { it.fenced(slices) }
        step(range, "fence") { source.connection.use { it.fence(slices) } }
        val moved = try {
            step(range, "drain") { source.connection.use(::drain) }
            val copiedTo = step(range, "catch up") {
                copy(source, into, slices)
                source.connection.use { it.top(slices) }
            }
            into.connection.use { it.unfence(slices) }
            SliceMove(map.version + 1, slices, sourceName, target, copiedTo).also { moved ->
                step(range, "switch") { primary.connection.use { it.switch(map.moving(slices, target), moved) } }
            }
        } catch (failed: Throwable) {
            // Not switched: the range stays where it was, and takes appends again.
            source.connection.use { it.unfence(slices) }
            into.connection.use { it.fence(fencedOnTarget) }
            throw failed
        }
        return moved
    }

    /**
     * Deletes each moved range's rows from its source, once the move is [after] old and every one of [readModels]
     * has read the source's feed past the range's last copied row, as saved in [offsets]: what it answers is the
     * moves it cleaned. A range that has since moved back to its source is kept, and marked cleaned.
     */
    fun cleanUp(offsets: OffsetStore, readModels: List<String>, after: Duration = 1.hours): List<SliceMove> {
        val now = time.now().toEpochMilli()
        val map = table.refresh()
        return primary.connection.use { it.pending() }
            .filter { (_, switchedAt) -> now - switchedAt >= after.inWholeMilliseconds }
            .map { it.first }
            .filter { move ->
                readModels.all { (offsets.load(ShardedJournal.progress(it, move.source)) ?: 0) >= move.copiedTo }
            }
            .onEach { move ->
                val home = move.slices.any { map.owner(it) == move.source }
                if (!home) step("${move.slices} ${move.source}", "clean up") {
                    database(move.source).connection.use { it.transaction { delete(move.slices) } }
                }
                primary.connection.use { it.cleaned(move.version) }
            }
    }

    /**
     * Waits until every append that could have missed the fence has ended. On Postgres, that is every transaction
     * running when the fence committed: once the oldest still running is newer than all of them. Elsewhere, [settle].
     */
    // The mover runs on its operator's thread, never in an actor's step: waiting there stalls nothing else.
    private fun drain(connection: Connection) {
        if (connection.metaData.databaseProductName != "PostgreSQL") return time.sleep(settle)
        val before = connection.snapshot("xmax")
        val running = { connection.snapshot("xmin") < before }
        // Asked again every POLL, on the inherited clock, until none is running or drainWithin has passed.
        Schedule.spaced<Boolean>(POLL).and(Schedule.upTo(drainWithin)).doWhile { still, _ -> still }.repeat(running)
        check(!running()) { "appends still running after $drainWithin; the move is abandoned and the range unfenced" }
    }

    private fun database(name: String): DataSource =
        requireNotNull(byName[name]) { "no database named $name; have ${byName.keys}" }

    private fun <T> step(range: String, step: String, block: () -> T): T {
        val started = TimeSource.Monotonic.markNow()
        return block().also { logInfo("surefold.journal.move $range: $step took ${started.elapsedNow()}") }
    }

    /**
     * Copies to [into] each of [slices]' events [source] has and it lacks, and each id's newest snapshot, on one
     * connection to each for the whole pass.
     */
    private fun copy(source: DataSource, into: DataSource, slices: IntRange) {
        source.connection.use { from ->
            into.connection.use { to ->
                val have = to.tops(slices)
                from.tops(slices).forEach { (id, top) ->
                    val had = have[id] ?: 0
                    if (top > had) to.transaction { insert(id, from.rows(id, had)) }
                    from.snapshot(id)?.let { (sequence, bytes) -> to.saveSnapshot(id, sequence, bytes) }
                }
            }
        }
    }
}

/** Each id in [slices] and its last sequence number here. */
@Suppress("MagicNumber") // column indices
private fun Connection.tops(slices: IntRange): Map<PersistenceId, Long> = statement(
    "select kind, id, max(seq_nr) from surefold_journal where slice between ? and ? group by kind, id",
    slices.first,
    slices.last,
) { select ->
    select.executeQuery().use { rows ->
        generateSequence {
            if (rows.next()) PersistenceId(rows.getString(1), rows.getString(2)) to rows.getLong(3) else null
        }.toMap()
    }
}

/** One event's row as it is copied: its sequence number, its bytes, and what its append carried, as `jsonb` text. */
private class Row(val sequence: Long, val bytes: ByteArray, val metadata: String?)

@Suppress("MagicNumber") // column indices
private fun Connection.rows(id: PersistenceId, after: Long): List<Row> = statement(
    "select seq_nr, bytes, metadata::text from surefold_journal " +
        "where kind = ? and id = ? and seq_nr > ? order by seq_nr",
    id.kind,
    id.id,
    after,
) { select ->
    select.executeQuery().use { rows ->
        generateSequence {
            if (rows.next()) Row(rows.getLong(1), rows.getBytes(2), rows.getString(3)) else null
        }.toList()
    }
}

private fun Connection.insert(id: PersistenceId, rows: List<Row>) {
    val slice = Slices.of(id)
    val sql = "insert into surefold_journal (kind, id, seq_nr, bytes, slice, metadata) " +
        "values (?, ?, ?, ?, ?, cast(? as jsonb))"
    prepareStatement(sql).use { insert ->
        rows.forEach { row -> insert.row(id.kind, id.id, row.sequence, row.bytes, slice, row.metadata) }
        insert.executeBatch()
    }
}

/** [id]'s newest snapshot here, if any: its sequence number and bytes. */
private fun Connection.snapshot(id: PersistenceId): Pair<Long, ByteArray>? =
    statement("select seq_nr, bytes from surefold_snapshot where kind = ? and id = ?", id.kind, id.id) { select ->
        select.executeQuery().use { rows -> if (rows.next()) rows.getLong(1) to rows.getBytes(2) else null }
    }

/** Keeps [id]'s snapshot at [sequence] here unless a newer one is kept, as `JdbcSnapshots.save` does. */
private fun Connection.saveSnapshot(id: PersistenceId, sequence: Long, bytes: ByteArray) {
    val replaced = statement(
        "update surefold_snapshot set seq_nr = ?, bytes = ? where kind = ? and id = ? and seq_nr < ?",
        sequence,
        bytes,
        id.kind,
        id.id,
        sequence,
    ) { it.executeUpdate() }
    if (replaced > 0) return
    statement(
        "insert into surefold_snapshot (kind, id, seq_nr, bytes) select ?, ?, ?, ? from (values (1)) " +
            "where not exists (select 1 from surefold_snapshot where kind = ? and id = ?)",
        id.kind,
        id.id,
        sequence,
        bytes,
        id.kind,
        id.id,
    ) { it.executeUpdate() }
}

/** Which of [slices] this database already refuses. */
private fun Connection.fenced(slices: IntRange): List<Int> = statement(
    "select slice from surefold_journal_fenced where slice between ? and ?",
    slices.first,
    slices.last,
) { select ->
    select.executeQuery().use { rows -> generateSequence { if (rows.next()) rows.getInt(1) else null }.toList() }
}

private fun Connection.fence(slices: Iterable<Int>) = transaction {
    prepareStatement(
        "insert into surefold_journal_fenced (slice) select ? from (values (1)) " +
            "where not exists (select 1 from surefold_journal_fenced where slice = ?)",
    ).use { insert ->
        slices.forEach { slice ->
            insert.setInt(1, slice)
            insert.setInt(2, slice)
            insert.addBatch()
        }
        insert.executeBatch()
    }
}

private fun Connection.unfence(slices: IntRange) {
    statement("delete from surefold_journal_fenced where slice between ? and ?", slices.first, slices.last) {
        it.executeUpdate()
    }
}

/** The highest ordering among [slices]' rows here, or 0. */
private fun Connection.top(slices: IntRange): Long = statement(
    "select coalesce(max(ordering), 0) from surefold_journal where slice between ? and ?",
    slices.first,
    slices.last,
) { select ->
    select.executeQuery().use { rows ->
        rows.next()
        rows.getLong(1)
    }
}

private fun Connection.snapshot(of: String): Long = statement("select pg_snapshot_$of(pg_current_snapshot())::text") {
    it.executeQuery().use { rows ->
        rows.next()
        rows.getString(1).toULong().toLong()
    }
}

private fun Connection.switch(next: SliceMap, move: SliceMove) = transaction {
    insertMap(next)
    statement(
        "insert into surefold_journal_moves (version, from_slice, to_slice, source, target, copied_to, switched_at, " +
            "cleaned) values (?, ?, ?, ?, ?, ?, ?, false)",
        move.version,
        move.slices.first,
        move.slices.last,
        move.source,
        move.target,
        move.copiedTo,
        clock.get().now().toEpochMilli(),
    ) { it.executeUpdate() }
}

/** Each move not yet cleaned, with when it switched. */
@Suppress("MagicNumber") // column indices
private fun Connection.pending(): List<Pair<SliceMove, Long>> = statement(
    "select version, from_slice, to_slice, source, target, copied_to, switched_at from surefold_journal_moves " +
        "where cleaned = false order by version",
) { select ->
    select.executeQuery().use { rows ->
        generateSequence {
            if (!rows.next()) return@generateSequence null
            SliceMove(
                rows.getLong(1),
                rows.getInt(2)..rows.getInt(3),
                rows.getString(4),
                rows.getString(5),
                rows.getLong(6),
            ) to rows.getLong(7)
        }.toList()
    }
}

private fun Connection.cleaned(version: Long) {
    statement("update surefold_journal_moves set cleaned = true where version = ?", version) { it.executeUpdate() }
}

/**
 * Deletes [slices]' rows and their ids' snapshots, and records the orderings they spanned as pruned, so a feed
 * started from before them reads past rather than waiting on them.
 */
private fun Connection.delete(slices: IntRange) {
    val ids = tops(slices).keys
    val span = statement(
        "select min(ordering), max(ordering) from surefold_journal where slice between ? and ?",
        slices.first,
        slices.last,
    ) { select ->
        select.executeQuery().use { rows ->
            rows.next()
            val first = rows.getLong(1)
            if (rows.wasNull()) null else first to rows.getLong(2)
        }
    } ?: return
    ids.forEach { id ->
        statement("delete from surefold_snapshot where kind = ? and id = ?", id.kind, id.id) { it.executeUpdate() }
    }
    statement("delete from surefold_journal where slice between ? and ?", slices.first, slices.last) {
        it.executeUpdate()
    }
    statement(
        "insert into surefold_journal_pruned (from_ordering, to_ordering) values (?, ?)",
        span.first,
        span.second,
    ) {
        it.executeUpdate()
    }
}

private val POLL = 10.milliseconds
