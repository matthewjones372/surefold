package dev.surefold.actor

import dev.surefold.fail.Either

/**
 * Which of [COUNT] slices an id's events live in (spec 0088): murmur3 of `"kind|id"`. The count and the hash are fixed
 * forever, since changing either would move ids away from the events they already wrote.
 */
object Slices {
    const val COUNT: Int = 1024

    fun of(id: PersistenceId): Int = murmur3("${id.kind}|${id.id}".toByteArray(Charsets.UTF_8)) and (COUNT - 1)

    /** The slices partition [partition] of [partitions] follows (spec 0106): an even share of them, in order. */
    fun partition(partition: Int, partitions: Int): IntRange {
        require(partitions in 1..COUNT) { "partitions must be in 1..$COUNT, was $partitions" }
        require(partition in 0 until partitions) { "partition must be in 0 until $partitions, was $partition" }
        return partition * COUNT / partitions until (partition + 1) * COUNT / partitions
    }

    /** Which of [partitions] partitions follows [id]'s slice. */
    fun partitionOf(id: PersistenceId, partitions: Int): Int {
        require(partitions in 1..COUNT) { "partitions must be in 1..$COUNT, was $partitions" }
        // The partition whose share starts at or below the slice: the inverse of [partition]'s bounds.
        return ((of(id) + 1) * partitions - 1) / COUNT
    }
}

/**
 * Which database takes each slice, at a [version] (spec 0105): contiguous [ranges] that together cover every slice
 * once, each owned by a database named in it.
 */
data class SliceMap(val version: Long, val ranges: List<Pair<IntRange, String>>) {
    private val owners: Array<String?> = arrayOfNulls(Slices.COUNT)

    init {
        ranges.forEach { (slices, owner) ->
            require(slices.first >= 0 && slices.last < Slices.COUNT) { "slices $slices are not all in 0..1023" }
            slices.forEach { slice ->
                require(owners[slice] == null) { "slice $slice is owned twice, by ${owners[slice]} and $owner" }
                owners[slice] = owner
            }
        }
        val unowned = owners.indices.filter { owners[it] == null }
        require(unowned.isEmpty()) { "slices owned by no database: $unowned" }
    }

    /** The database that takes [slice]. */
    fun owner(slice: Int): String = checkNotNull(owners[slice])

    /** The databases that own any slice. */
    val databases: Set<String>
        get() = ranges.mapTo(LinkedHashSet()) { it.second }

    /**
     * This map with [slices] given to [owner], as the next version: adjacent ranges of one owner are merged, so the map
     * stays as short as the moves allow.
     */
    fun moving(slices: IntRange, owner: String): SliceMap {
        val next = Array(Slices.COUNT) { if (it in slices) owner else owner(it) }
        return SliceMap(version + 1, runs(next.asList()))
    }

    companion object {
        /**
         * What spec 0088 gave [names] by formula, contiguous ranges in the order given: what a journal created before
         * spec 0105 routes by, so it goes on routing every id where it did.
         */
        fun even(names: List<String>, version: Long = 1): SliceMap {
            require(names.isNotEmpty()) { "sharded over no databases" }
            require(names.size <= Slices.COUNT) { "${names.size} databases, but only ${Slices.COUNT} slices" }
            val repeated = names.groupBy { it }.filterValues { it.size > 1 }.keys
            require(repeated.isEmpty()) { "databases named twice: $repeated" }
            return SliceMap(version, runs((0 until Slices.COUNT).map { names[it * names.size / Slices.COUNT] }))
        }

        private fun runs(owners: List<String>): List<Pair<IntRange, String>> =
            owners.indices.fold(mutableListOf<Pair<IntRange, String>>()) { runs, slice ->
                val last = runs.lastOrNull()
                if (last != null && last.second == owners[slice]) {
                    runs[runs.size - 1] = last.first.first..slice to last.second
                } else {
                    runs += slice..slice to owners[slice]
                }
                runs
            }
    }
}

/**
 * Where a sharded journal learns its [SliceMap] (spec 0105). [current] may answer one a moment old; [refresh] reads it
 * again, as after a database refused an append for a slice it no longer takes.
 */
interface SliceTable {
    fun current(): SliceMap

    fun refresh(): SliceMap = current()

    companion object {
        /** A map that never changes: the formula of spec 0088 when nothing else is given. */
        fun fixed(map: SliceMap): SliceTable =
            object : SliceTable {
                override fun current(): SliceMap = map
            }
    }
}

/**
 * A journal that cannot take an append now, and may soon: its caller retries, as when a database is down. A range of
 * slices being moved answers this for the seconds its final copy takes (spec 0105).
 */
open class JournalUnavailable(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A database refused an append for [slice], which it does not take: the slice has moved, or is moving, away. */
class SliceElsewhere(val slice: Int) : JournalUnavailable("slice $slice is not taken by this database")

/** Named stores behind a [SliceTable]: which store an id's slice is owned by now. */
private class Routing<T : Any>(databases: List<Pair<String, T>>, table: SliceTable?) {
    val named: List<Pair<String, T>> = databases.toList()
    private val byName = named.toMap()
    private val slices = table ?: SliceTable.fixed(SliceMap.even(named.map { it.first }))

    init {
        require(named.isNotEmpty()) { "sharded over no databases" }
        require(byName.size == named.size) { "databases named twice: ${named.map { it.first }}" }
    }

    fun name(id: PersistenceId): String = slices.current().owner(Slices.of(id))

    fun of(id: PersistenceId): T = store(name(id))

    /** The store now owning [id]'s slice, read afresh; null when that is still [was]. */
    fun moved(id: PersistenceId, was: String): T? =
        slices.refresh().owner(Slices.of(id)).takeIf { it != was }?.let(::store)

    private fun store(name: String): T = checkNotNull(byName[name]) { "the slice table names $name, not given here" }
}

/**
 * A journal split across databases by id (spec 0088): each id's events, and each append's optimistic check, stay in the
 * one database its slice belongs to. There is no order across databases; each has its own feed in [feeds].
 *
 * Which database owns which slices is [slices]' (spec 0105), by default the fixed ranges of spec 0088. A database that
 * refuses an append for a slice it has given away sends the append on to the slice's new owner once, after reading the
 * table again; while a slice is still moving, the append is [JournalUnavailable].
 */
class ShardedJournal(databases: List<Pair<String, Journal>>, slices: SliceTable? = null) : Journal, JournalPruning {
    private val routing = Routing(databases, slices)

    /** Each database's own feed, by name, for one projection per database. */
    val feeds: List<Pair<String, JournalFeed>> by lazy {
        routing.named.map { (name, journal) ->
            name to requireNotNull(journal as? JournalFeed) { "the journal of $name has no feed" }
        }
    }

    /** The name of the database [id]'s events live in. */
    fun database(id: PersistenceId): String = routing.name(id)

    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>): Either<JournalConflict, Long> {
        val name = routing.name(id)
        return try {
            routing.of(id).append(id, expected, events)
        } catch (elsewhere: SliceElsewhere) {
            val owner =
                routing.moved(id, name) ?: throw JournalUnavailable("slice ${elsewhere.slice} is moving", elsewhere)
            owner.append(id, expected, events)
        }
    }

    override fun read(id: PersistenceId, from: Long): List<StoredEvent> = routing.of(id).read(id, from)

    /** Deletes in [id]'s database, if that journal prunes; [readTo] is an offset in that database's feed. */
    override fun deleteTo(id: PersistenceId, sequence: Long, readTo: Long) {
        (routing.of(id) as? JournalPruning)?.deleteTo(id, sequence, readTo)
    }

    companion object {
        /** What a read model [name] saves its offset under for [database]'s feed. */
        fun progress(name: String, database: String): String = "$name@$database"

        /** What partition [partition] of read model [name] saves its offset under for [database]'s feed (spec 0106). */
        fun progress(name: String, database: String, partition: Int): String = "$name@$database#$partition"
    }
}

/**
 * Snapshots split across databases as [ShardedJournal] splits events: the same names, in the same order or behind the
 * same [slices], co-locate.
 */
class ShardedSnapshots(databases: List<Pair<String, SnapshotStore>>, slices: SliceTable? = null) : SnapshotStore {
    private val routing = Routing(databases, slices)

    override fun save(id: PersistenceId, sequence: Long, bytes: ByteArray) = routing.of(id).save(id, sequence, bytes)

    override fun latest(id: PersistenceId): Snapshot? = routing.of(id).latest(id)
}

/** MurmurHash3, x86 32-bit, seed 0: copied in rather than depended on, and pinned by known values in its test. */
@Suppress("MagicNumber")
internal fun murmur3(data: ByteArray): Int {
    val c1 = -0x3361d2af
    val c2 = 0x1b873593
    var h = 0
    val blocks = data.size / 4
    for (i in 0 until blocks) {
        val at = i * 4
        var k =
            (data[at].toInt() and 0xff) or
                ((data[at + 1].toInt() and 0xff) shl 8) or
                ((data[at + 2].toInt() and 0xff) shl 16) or
                ((data[at + 3].toInt() and 0xff) shl 24)
        k = Integer.rotateLeft(k * c1, 15) * c2
        h = Integer.rotateLeft(h xor k, 13) * 5 + -0x19ab949c
    }
    var tail = 0
    for (i in data.size - 1 downTo blocks * 4) tail = (tail shl 8) or (data[i].toInt() and 0xff)
    if (data.size and 3 != 0) h = h xor (Integer.rotateLeft(tail * c1, 15) * c2)
    h = h xor data.size
    h = h xor (h ushr 16)
    h *= -0x7a143595
    h = h xor (h ushr 13)
    h *= -0x3d4d51cb
    return h xor (h ushr 16)
}
