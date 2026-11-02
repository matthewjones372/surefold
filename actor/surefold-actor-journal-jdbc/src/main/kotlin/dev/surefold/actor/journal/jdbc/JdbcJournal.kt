package dev.surefold.actor.journal.jdbc

import dev.surefold.Carriers
import dev.surefold.actor.FeedEvent
import dev.surefold.actor.Journal
import dev.surefold.actor.JournalConflict
import dev.surefold.actor.JournalPruning
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.SliceElsewhere
import dev.surefold.actor.SlicedFeed
import dev.surefold.actor.Slices
import dev.surefold.actor.StoredEvent
import dev.surefold.clock
import dev.surefold.fail.Either
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.logWarn
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.SQLException
import java.sql.Types
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.sql.DataSource
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** The SQL state every database gives a duplicate key: another writer took the sequence number first. */
internal const val DUPLICATE_KEY = "23505"

/**
 * How long a gap is held on Postgres before its horizon is trusted: an append's transaction is given its id a moment
 * after it takes its `ordering`.
 */
private val SETTLE = 1.seconds

/**
 * A [Journal] in one `surefold_journal` table, reached through [dataSource], so that every node that reaches the same
 * database reads the same events. The table ships in the jar as a Liquibase changelog in formatted SQL,
 * `surefold/journal/jdbc/postgres.sql`; the service's changelog includes it, and nothing here creates it.
 *
 * Of two writers for one id, the table's primary key decides: an append inserts its first event only if the one it
 * expects to follow is there, and two that both expect it collide on the key, so exactly one commits. A database
 * that cannot answer throws its [SQLException].
 *
 * Each row carries its id's slice (spec 0105), and an append for a slice listed in `surefold_journal_fenced`, one this
 * database has given to another, is refused in the append's own statement with [SliceElsewhere].
 *
 * It is a [JournalFeed] too (spec 0075), its offsets the table's `ordering`. An append takes its `ordering` when it
 * inserts and is seen when it commits, so a smaller one can commit after a larger one is read: the feed answers
 * nothing past a missing `ordering` until it fills, or until it is known never to: then it is taken for an append
 * that never committed and passed, with a warning. On Postgres that is once every transaction running when the gap
 * was first seen has ended (spec 0100), so an append whose writer is slow to commit, however slow, is waited for,
 * and one rolled back is passed within a second; [longestAppend] bounds the wait for a writer that never ends. On a
 * database without transaction snapshots it is once the gap has been missing for [gapTimeout].
 *
 * With [groupCommit] (spec 0108), appends from many ids share a transaction and one commit, each under a savepoint of
 * its own, so each still answers as it would have alone; without it, every append is a transaction of its own.
 */
class JdbcJournal(
    private val dataSource: DataSource,
    private val gapTimeout: Duration = 10.seconds,
    private val longestAppend: Duration = 10.minutes,
    groupCommit: GroupCommit? = null,
) :
    Journal,
    SlicedFeed,
    JournalPruning {

    private val time = clock.get()

    private val committer = groupCommit?.let { settings ->
        Committer(
            dataSource,
            settings,
            group = { connection, appends -> connection.appendAll(appends) },
            alone = { append -> alone(append) },
        )
    }

    /** A missing `ordering`: when it was first found so, and on Postgres the snapshot's `xmax` then. */
    private class Gap(val seen: Instant, val xmax: Long?)

    /** Each `ordering` found missing. */
    private val gaps = ConcurrentHashMap<Long, Gap>()

    // Whether the database has transaction snapshots to read, asked once.
    @Volatile
    private var snapshots: Boolean? = null

    /** The `ordering`s missing for longer than [gapTimeout], which the feed reads past. */
    private val passed = ConcurrentHashMap.newKeySet<Long>()

    // The orderings the watermark has settled, and the lock one walk at a time holds (spec 0106): a lock, not a
    // monitor, since a feed's reader may be a virtual thread.
    private val settling = ReentrantLock()
    private var settled: LongRange? = null

    /**
     * Appends [events] with what the appending thread carries (spec 0123): an entity's command handled inside a trace
     * writes its events with that trace.
     */
    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        val append = Append(id, expected, events, Metadata.write(Carriers.capture()))
        return committer?.append(append) ?: alone(append)
    }

    /** How many appends wait for a group to commit them, for tests. */
    internal fun queued(): Int = committer?.queued() ?: 0

    private fun alone(append: Append): Either<JournalConflict, Long> =
        dataSource.connection.use { connection ->
            val (id, expected, events) = Triple(append.id, append.expected, append.events)
            connection.autoCommit = false
            val appended = try {
                connection.insert(append)
            } catch (failed: SQLException) {
                connection.rollback()
                if (failed.sqlState != DUPLICATE_KEY) throw failed
                false
            } catch (elsewhere: SliceElsewhere) {
                connection.rollback()
                throw elsewhere
            }
            if (appended) {
                connection.commit()
                (expected + events.size).right()
            } else {
                connection.rollback()
                JournalConflict(id, expected, connection.last(id)).left()
            }
        }

    @Suppress("MagicNumber") // column indices
    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = dataSource.connection.use { connection ->
        connection.statement(
            "select seq_nr, bytes, metadata::text from surefold_journal where kind = ? and id = ? and seq_nr >= ? " +
                "order by seq_nr",
            id.kind,
            id.id,
            from,
        ) { select ->
            select.executeQuery().use { rows ->
                generateSequence {
                    if (!rows.next()) return@generateSequence null
                    StoredEvent(rows.getLong(1), rows.getBytes(2), Metadata.read(rows.getString(3)))
                }.toList()
            }
        }
    }

    override fun after(kind: String, offset: Long, limit: Int): List<FeedEvent> = upToMark(kind, null, offset, limit)

    override fun after(kind: String, slices: IntRange, offset: Long, limit: Int): List<FeedEvent> =
        upToMark(kind, slices, offset, limit)

    /** Up to [limit] events of [kind], in [slices] if any, after [offset] and at or below the watermark. */
    private fun upToMark(kind: String, slices: IntRange?, offset: Long, limit: Int): List<FeedEvent> {
        val mark = watermark(offset)
        if (mark <= offset) return emptyList()
        return dataSource.connection.use { connection ->
            val columns = "select ordering, id, seq_nr, bytes, metadata::text from surefold_journal where kind = ? "
            val window = "and ordering > ? and ordering <= ? order by ordering fetch first ? rows only"
            if (slices == null) {
                connection.statement(columns + window, kind, offset, mark, limit) { it.events(kind) }
            } else {
                connection.statement(
                    columns + "and slice between ? and ? " + window,
                    kind,
                    slices.first,
                    slices.last,
                    offset,
                    mark,
                    limit,
                ) { it.events(kind) }
            }
        }
    }

    @Suppress("MagicNumber") // column indices
    private fun PreparedStatement.events(kind: String): List<FeedEvent> = executeQuery().use { rows ->
        generateSequence {
            if (!rows.next()) return@generateSequence null
            FeedEvent(
                rows.getLong(1),
                PersistenceId(kind, rows.getString(2)),
                rows.getLong(3),
                rows.getBytes(4),
                Metadata.read(rows.getString(5)),
            )
        }.toList()
    }

    /**
     * The watermark above [offset] (spec 0106): the highest `ordering` through which every row after [offset] is either
     * committed or known never to be, so a feed reads up to it and never waits on a gap of its own. One walk over the
     * orderings alone, of every kind, serves every feed on this journal: what it has settled is kept, and each call
     * goes on from there, a few batches at most. An [offset] below what is kept is walked up to it first; one above it
     * starts afresh, since what lies between was never walked.
     */
    fun watermark(offset: Long): Long = settling.withLock {
        dataSource.connection.use { connection ->
            val kept = settled
            when {
                // Nothing kept, or nothing kept reaches this offset: what is between is unknown, so start from here.
                kept == null || offset > kept.last -> settled = offset..offset

                offset < kept.first -> {
                    val reached = connection.settle(offset, kept.first)
                    if (reached < kept.first) return@use reached
                    settled = offset..kept.last
                }
            }
            val (from, to) = checkNotNull(settled).let { it.first to it.last }
            connection.settle(to, Long.MAX_VALUE).also { mark -> settled = from..mark }
        }
    }

    /**
     * The highest ordering from [from] up to at most [bound] through which nothing can still appear: each ordering is
     * present, or pruned, or a gap already passed, or one the gap rule of spec 0100 passes now. Every gap in a batch
     * is looked at, not only the first that holds: each gap's wait starts when it is first seen, so gaps seen together
     * pass together, not one gapTimeout after another.
     */
    private fun Connection.settle(from: Long, bound: Long): Long {
        var at = from
        repeat(WALK_BATCHES) {
            val present = statement(
                "select ordering from surefold_journal where ordering > ? and ordering <= ? order by ordering " +
                    "fetch first ? rows only",
                at,
                bound,
                WALK_BATCH,
            ) { select ->
                select.executeQuery().use { rows ->
                    generateSequence { if (rows.next()) rows.getLong(1) else null }.toList()
                }
            }
            if (present.isEmpty()) return at
            // Read after the rows: a deletion commits its rows' removal and its span together, so a row missing here
            // was either never committed or is inside a span this reads.
            val walk = Walk(at, pruned(at, present.last())) { horizon() }
            present.forEach(walk::present)
            if (walk.held || present.size < WALK_BATCH) return walk.through
            at = walk.through
        }
        return at
    }

    /** One batch of the watermark's walk: how far it has settled, and whether a gap holds it there. */
    private inner class Walk(from: Long, pruned: List<LongRange>, horizon: () -> Horizon?) {
        var through = from
            private set
        var held = false
            private set
        private var previous = from
        private val spans = ArrayDeque(pruned)
        private val now by lazy { time.now() }
        private val horizon by lazy(horizon)

        fun present(ordering: Long) {
            if (ordering > previous + 1) missing(previous + 1, ordering - 1).forEach(::gap)
            if (gaps.isNotEmpty()) gaps.remove(ordering)
            settled(ordering)
            previous = ordering
        }

        private fun gap(ordering: Long) {
            if (ordering in passed || !holds(ordering, now, horizon)) settled(ordering) else held = true
        }

        private fun settled(ordering: Long) {
            if (!held) through = ordering
        }

        /** The orderings from [first] to [last] outside every pruned span; spans behind [first] are let go. */
        private fun missing(first: Long, last: Long): List<Long> {
            while (spans.isNotEmpty() && spans.first().last < first) spans.removeFirst()
            val found = mutableListOf<Long>()
            var at = first
            for (span in spans) {
                if (span.first > last || at > last) break
                while (at < span.first) found += at++
                at = maxOf(at, span.last + 1)
            }
            while (at <= last) found += at++
            return found
        }
    }

    /** Whether the gap at [ordering] is still to be waited on; one that is not is passed, with a warning. */
    private fun holds(ordering: Long, now: Instant, horizon: Horizon?): Boolean {
        val gap = gaps.computeIfAbsent(ordering) { Gap(now, horizon?.xmax) }
        val waited = java.time.Duration.between(gap.seen, now)
        val holding = holds(gap, waited.toKotlinDuration(), horizon)
        if (!holding && passed.add(ordering)) {
            gaps.remove(ordering)
            logWarn("the feed passes ordering $ordering, missing for $waited: an append never committed")
        }
        return holding
    }

    /** The spans deleted rows left between [after] and [upTo]. */
    private fun Connection.pruned(after: Long, upTo: Long): List<LongRange> = statement(
        "select from_ordering, to_ordering from surefold_journal_pruned where to_ordering > ? and from_ordering < ? " +
            "order by from_ordering",
        after,
        upTo,
    ) { select ->
        select.executeQuery().use { rows ->
            generateSequence { if (rows.next()) rows.getLong(1)..rows.getLong(2) else null }.toList()
        }
    }

    /**
     * Deletes [id]'s events up to [sequence], never its newest, and records the orderings the deleted rows spanned in
     * `surefold_journal_pruned`, in the same transaction, so the feed reads past them rather than waiting on them as
     * gaps.
     */
    override fun deleteTo(id: PersistenceId, sequence: Long, readTo: Long) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                connection.prune(id, minOf(sequence, connection.readTo(id, readTo), connection.last(id) - 1))
                connection.commit()
            } catch (failed: SQLException) {
                connection.rollback()
                throw failed
            }
        }
    }

    /** The last sequence number of [id] whose ordering is at most [ordering], or 0 when there is none. */
    private fun Connection.readTo(id: PersistenceId, ordering: Long): Long = statement(
        "select coalesce(max(seq_nr), 0) from surefold_journal where kind = ? and id = ? and ordering <= ?",
        id.kind,
        id.id,
        ordering,
    ) { select ->
        select.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }

    private fun Connection.prune(id: PersistenceId, upTo: Long) {
        val span = statement(
            "select min(ordering), max(ordering) from surefold_journal where kind = ? and id = ? and seq_nr <= ?",
            id.kind,
            id.id,
            upTo,
        ) { select ->
            select.executeQuery().use { rows ->
                rows.next()
                val first = rows.getLong(1)
                if (rows.wasNull()) null else first to rows.getLong(2)
            }
        } ?: return
        statement("delete from surefold_journal where kind = ? and id = ? and seq_nr <= ?", id.kind, id.id, upTo) {
            it.executeUpdate()
        }
        val (from, to) = span
        statement("insert into surefold_journal_pruned (from_ordering, to_ordering) values (?, ?)", from, to) {
            it.executeUpdate()
        }
    }

    /** Postgres's view of which transactions are running: every one below [xmin] has ended. */
    private class Horizon(val xmin: Long, val xmax: Long)

    /**
     * Whether a gap is still to be waited on. A gap's `ordering` was taken by a transaction running when the gap was
     * first seen, or by one that had not yet been given its id (a moment, which [SETTLE] covers): once the oldest
     * running transaction is newer than every one then, nothing can fill it.
     */
    private fun holds(gap: Gap, waited: Duration, horizon: Horizon?): Boolean = when {
        horizon == null || gap.xmax == null -> waited < gapTimeout
        waited < SETTLE -> true
        else -> horizon.xmin < gap.xmax && waited < longestAppend
    }

    /** The running transactions' horizon, on a database that has one; null on any other. */
    private fun Connection.horizon(): Horizon? {
        if (snapshots == null) snapshots = metaData.databaseProductName == "PostgreSQL"
        if (snapshots != true) return null
        return statement(
            "select pg_snapshot_xmin(s)::text, pg_snapshot_xmax(s)::text from (select pg_current_snapshot() as s) t",
        ) { select ->
            select.executeQuery().use { rows ->
                rows.next()
                Horizon(rows.getString(1).toULong().toLong(), rows.getString(2).toULong().toLong())
            }
        }
    }

    /**
     * Inserts [events] after [expected], the first only if the event it follows is there: whether they all went in.
     * A writer ahead of the journal inserts nothing, and one behind it collides on the key.
     */

    /**
     * A group's appends (spec 0108), in one statement batch: one round trip for them all. Each inserts every one of
     * its events, or none: none when its expected sequence is not the last, answered as a conflict, or when its slice
     * is fenced. A duplicate key, two writers racing for one id, throws for the whole group, which the committer then
     * makes one append at a time.
     */
    private fun Connection.appendAll(appends: List<Append>): List<Result<Either<JournalConflict, Long>>> {
        val written = appends.withIndex().filter { (_, append) -> append.events.isNotEmpty() }
        val counts = if (written.isEmpty()) IntArray(0) else prepareStatement(
            "insert into surefold_journal (kind, id, seq_nr, bytes, slice, metadata) " +
                "select ?, ?, ? + event.n, event.bytes, ?, cast(? as jsonb) " +
                "from unnest(?::bytea[]) with ordinality as event(bytes, n) " +
                "where (? = 0 or exists (select 1 from surefold_journal where kind = ? and id = ? and seq_nr = ?)) " +
                "and not exists (select 1 from surefold_journal_fenced where slice = ?)",
        ).use { insert ->
            written.forEach { (_, append) ->
                val slice = Slices.of(append.id)
                insert.row(
                    append.id.kind, append.id.id, append.expected, slice, append.metadata,
                    createArrayOf("bytea", append.events.toTypedArray()),
                    append.expected, append.id.kind, append.id.id, append.expected, slice,
                )
            }
            insert.executeBatch()
        }
        val inserted = written.map { it.index }.zip(counts.toList()).toMap()
        return appends.mapIndexed { at, append ->
            val count = inserted[at]
            when {
                append.events.isEmpty() && last(append.id) == append.expected -> Result.success(append.expected.right())

                count != null && count == append.events.size ->
                    Result.success((append.expected + append.events.size).right())

                fenced(Slices.of(append.id)) -> Result.failure(SliceElsewhere(Slices.of(append.id)))

                else -> Result.success(JournalConflict(append.id, append.expected, last(append.id)).left())
            }
        }
    }

    private fun Connection.insert(append: Append): Boolean {
        val (id, expected, events) = Triple(append.id, append.expected, append.events)
        if (events.isEmpty()) return last(id) == expected
        val slice = Slices.of(id)
        val first = statement(
            "insert into surefold_journal (kind, id, seq_nr, bytes, slice, metadata) " +
                "select ?, ?, ?, ?, ?, cast(? as jsonb) from (values (1)) " +
                "where (? = 0 or exists (select 1 from surefold_journal where kind = ? and id = ? and seq_nr = ?)) " +
                "and not exists (select 1 from surefold_journal_fenced where slice = ?)",
            id.kind,
            id.id,
            expected + 1,
            events.first(),
            slice,
            append.metadata,
            expected,
            id.kind,
            id.id,
            expected,
            slice,
        ) { it.executeUpdate() }
        if (first == 0) {
            if (fenced(slice)) throw SliceElsewhere(slice)
            return false
        }
        events.drop(1).forEachIndexed { i, bytes ->
            statement(
                "insert into surefold_journal (kind, id, seq_nr, bytes, slice, metadata) " +
                    "values (?, ?, ?, ?, ?, cast(? as jsonb))",
                id.kind,
                id.id,
                expected + i + 2,
                bytes,
                slice,
                append.metadata,
            ) { it.executeUpdate() }
        }
        return true
    }

    /** Whether this database refuses [slice]'s appends, having given it to another (spec 0105). */
    private fun Connection.fenced(slice: Int): Boolean =
        statement("select 1 from surefold_journal_fenced where slice = ?", slice) { select ->
            select.executeQuery().use { rows -> rows.next() }
        }

    private fun Connection.last(id: PersistenceId): Long = statement(
        "select coalesce(max(seq_nr), 0) from surefold_journal where kind = ? and id = ?",
        id.kind,
        id.id,
    ) { select ->
        select.executeQuery().use { rows ->
            rows.next()
            rows.getLong(1)
        }
    }
}

/** How many orderings the watermark reads at a time, and how many times a call goes on before answering. */
private const val WALK_BATCH = 10_000
private const val WALK_BATCHES = 4

/** [values] bound to the statement's parameters in order, and added as one row of its batch. */
internal fun PreparedStatement.row(vararg values: Any?) {
    values.forEachIndexed { i, value -> bind(i + 1, value) }
    addBatch()
}

/** [sql] with [values] bound to its parameters in order, handed to [use] and closed after. */
internal fun <T> Connection.statement(sql: String, vararg values: Any?, use: (PreparedStatement) -> T): T =
    prepareStatement(sql).use { statement ->
        values.forEachIndexed { i, value -> statement.bind(i + 1, value) }
        use(statement)
    }

/** A null is bound as text: the only nullable parameter is an append's metadata, cast to `jsonb` in the statement. */
private fun PreparedStatement.bind(index: Int, value: Any?) =
    if (value == null) setNull(index, Types.VARCHAR) else setObject(index, value)
