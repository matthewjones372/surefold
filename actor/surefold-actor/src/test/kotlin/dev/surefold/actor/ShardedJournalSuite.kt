package dev.surefold.actor

import dev.surefold.fail.Either
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInRange
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.shouldThrow
import dev.surefold.junit.withClue

private fun two() = listOf("db-a" to InMemoryJournal(), "db-b" to InMemoryJournal())

/** Spec 0088: a journal split across databases by id. */
object ShardedJournalSuite : Suite() {
    init {
        test("murmur3 answers the reference values, so a slice never moves") {
            mapOf(
                    "" to 0,
                    "a" to 1009084850,
                    "ab" to -1681926305,
                    "abc" to -1277324294,
                    "abcd" to 1139631978,
                    "hello" to 613153351,
                    "account|acc-1" to 1232564952,
                    "transfer|7f3c0e2a-1b4d-4c55-9e0e-1f2a3b4c5d6e" to -1796319154,
                    "The quick brown fox jumps over the lazy dog" to 776992547,
                )
                .entries
                .forEach { (text, hash) -> withClue(text) { murmur3(text.toByteArray()) shouldBe hash } }
        }

        test("an id's events are written to one database, and read back from it") {
            val databases = two()
            val journal = ShardedJournal(databases)
            val id = PersistenceId("account", "acc-1")
            journal.append(id, 0, listOf(byteArrayOf(1), byteArrayOf(2))) shouldBe Either.Right(2L)

            val (home, other) = databases.partition { it.first == journal.database(id) }
            home.single().second.read(id).map { it.sequence } shouldContainExactly listOf(1L, 2L)
            other.single().second.read(id).shouldBeEmpty()
            journal.read(id, from = 2).map { it.sequence } shouldContainExactly listOf(2L)
        }

        test("the same names in the same order route an id to the same database every time") {
            val ids = (1..1_000).map { PersistenceId("account", "acc-$it") }
            val first = ShardedJournal(two())
            val second = ShardedJournal(two())
            ids.map(first::database) shouldContainExactly ids.map(second::database)
        }

        test("a conflict is still a conflict") {
            val journal = ShardedJournal(two())
            val id = PersistenceId("account", "acc-1")
            journal.append(id, 0, listOf(byteArrayOf(1)))
            journal.append(id, 0, listOf(byteArrayOf(2))) shouldBe Either.Left(JournalConflict(id, 0, 1))
        }

        test("ten thousand ids split within five percent of even") {
            listOf(2, 3, 4).forEach { n ->
                val journal = ShardedJournal((1..n).map { "db-$it" to InMemoryJournal() })
                val counts =
                    (1..10_000).groupingBy { journal.database(PersistenceId("account", "acc-$it")) }.eachCount()
                val even = 10_000 / n
                withClue("$n databases: $counts") {
                    counts.size shouldBe n
                    counts.values.forEach { it shouldBeInRange (even * 95 / 100)..(even * 105 / 100) }
                }
            }
        }

        test("a snapshot sits in the database its id's events do") {
            val stores = listOf("db-a" to InMemorySnapshots(), "db-b" to InMemorySnapshots())
            val journal = ShardedJournal(two())
            val snapshots = ShardedSnapshots(stores)
            (1..50)
                .map { PersistenceId("account", "acc-$it") }
                .forEach { id ->
                    snapshots.save(id, 3, byteArrayOf(3))
                    snapshots.latest(id)?.sequence shouldBe 3
                    stores.single { it.first == journal.database(id) }.second.latest(id)?.sequence shouldBe 3
                }
        }

        test("pruning deletes in the id's own database") {
            val journal = ShardedJournal(two())
            val id = PersistenceId("account", "acc-1")
            journal.append(id, 0, (1..5).map { byteArrayOf(it.toByte()) })
            journal.deleteTo(id, 3)
            journal.read(id).map { it.sequence } shouldContainExactly listOf(4L, 5L)
        }

        test("the formula's ranges, as a slice map, own each slice where spec 0088's formula put it") {
            listOf(1, 2, 3, 4, 7).forEach { n ->
                val names = (1..n).map { "db-$it" }
                val map = SliceMap.even(names)
                map.ranges.size shouldBe n
                (0 until Slices.COUNT).forEach { slice -> map.owner(slice) shouldBe names[slice * n / Slices.COUNT] }
            }
        }

        test(
            "a range moved takes the next version and merges with its neighbours, and a map missing a slice is refused",
        ) {
            val two = SliceMap.even(listOf("db-a", "db-b"))
            val moved = two.moving(0..255, "db-c")
            val merged = moved.moving(0..255, "db-a")
            val three = listOf(0..255 to "db-c", 256..511 to "db-a", 512..1023 to "db-b")
            moved.version shouldBe 2
            moved.ranges shouldContainExactly three
            merged.ranges shouldContainExactly listOf(0..511 to "db-a", 512..1023 to "db-b")
            shouldThrow<IllegalArgumentException> { SliceMap(1, listOf(0..1022 to "db-a")) }
            shouldThrow<IllegalArgumentException> { SliceMap(1, listOf(0..1023 to "db-a", 5..5 to "db-b")) }
        }

        test("an append refused for a slice given away goes to its new owner, or is unavailable while it moves") {
            val a = InMemoryJournal()
            val b = InMemoryJournal()
            val refusing =
                object : Journal by a {
                    override fun append(id: PersistenceId, expected: Long, events: List<ByteArray>) =
                        throw SliceElsewhere(Slices.of(id))
                }
            val before = SliceMap.even(listOf("db-a", "db-b"))
            var after = before
            val table =
                object : SliceTable {
                    // A node that has not yet read the new version, until it is refused.
                    override fun current() = before

                    override fun refresh() = after
                }
            val journal = ShardedJournal(listOf("db-a" to refusing, "db-b" to b), table)
            val id =
                (1..100).map { PersistenceId("account", "acc-$it") }.first { before.owner(Slices.of(it)) == "db-a" }

            shouldThrow<JournalUnavailable> { journal.append(id, 0, listOf(byteArrayOf(1))) }
            after = before.moving(0..1023, "db-b")
            journal.append(id, 0, listOf(byteArrayOf(1))) shouldBe Either.Right(1L)
            b.read(id).map { it.sequence } shouldContainExactly listOf(1L)
        }

        test("every slice is in exactly the partition partitionOf names, for any count of partitions") {
            val ids = (1..5_000).map { PersistenceId("account", "acc-$it") }
            listOf(1, 2, 3, 7, 8, 100, 1023, 1024).forEach { n ->
                val slices = (0 until n).flatMap { Slices.partition(it, n) }
                slices shouldContainExactly (0 until Slices.COUNT).toList()
                ids.forEach { id ->
                    withClue("$id of $n") {
                        Slices.of(id) shouldBeInRange Slices.partition(Slices.partitionOf(id, n), n)
                    }
                }
            }
        }

        test("pruning after partitioned read models waits on the partition that follows the id") {
            val journal = ShardedJournal(two())
            val offsets = InMemoryOffsets()
            val prune = Prune.after(offsets, journal, 4, "totals")
            val id = PersistenceId("account", "acc-1")
            val partition = Slices.partitionOf(id, 4)
            (0 until 4).forEach { offsets.save(ShardedJournal.progress("totals", journal.database(id), it), 10L + it) }

            prune.readTo(id) shouldBe 10L + partition
        }

        test("databases named twice, or none at all, are refused") {
            shouldThrow<IllegalArgumentException> { ShardedJournal(emptyList()) }
            shouldThrow<IllegalArgumentException> {
                ShardedJournal(listOf("a" to InMemoryJournal(), "a" to InMemoryJournal()))
            }
        }
    }
}
