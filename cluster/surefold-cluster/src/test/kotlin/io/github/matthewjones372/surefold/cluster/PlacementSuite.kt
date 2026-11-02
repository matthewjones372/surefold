package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldBeInRange
import dev.surefold.junit.shouldBeLessThanOrEqual
import dev.surefold.junit.shouldBeNull
import dev.surefold.junit.withClue

private fun up(n: Int, status: Status = Status.Up) = Member(Node("n$n", "10.0.0.$n", 25520), n.toLong(), status, n)

private fun owners(members: List<Member>, shards: Int = 256) =
    List(shards) { Placement.owner("order", it, members) }

object PlacementSuite : Suite() {
    init {
        test("an id is always in the same shard, and every shard is one of the kind's") {
            val shards = (1..10_000).map { Placement.shardOf("order-$it", 256) }

            shards.forAll { it shouldBeInRange 0..255 }
            (1..10_000).map { Placement.shardOf("order-$it", 256) } shouldBe shards
            withClue("every shard has some id") { shards.toSet().size shouldBe 256 }
        }

        test("every node that has the same members agrees on every owner, whatever order it has them in") {
            val five = (1..5).map { up(it) }

            owners(five.shuffled()) shouldBe owners(five)
            owners(five.reversed()) shouldBe owners(five)
        }

        test("the shards are spread across the members") {
            val counts = owners((1..5).map { up(it) }).groupingBy { it }.eachCount()

            counts.keys.size shouldBe 5
            counts.values.forAll { it shouldBeInRange 30..80 }
        }

        test("a member joining takes about its share of shards, and no shard moves between the others") {
            val before = owners((1..5).map { up(it) })
            val after = owners((1..6).map { up(it) })

            val moved = before.indices.filter { before[it] != after[it] }
            moved.map { after[it] }.toSet() shouldBe setOf(up(6).node)
            moved.size shouldBeInRange 20..70
        }

        test("a member leaving gives up only its own shards") {
            val before = owners((1..5).map { up(it) })
            val after = owners((1..4).map { up(it) })

            before.indices.filter { before[it] != after[it] }.filterNot { before[it] == up(5).node }.shouldBeEmpty()
        }

        test("only Up members own shards, and with none up no shard has an owner") {
            val members = listOf(up(1), up(2, Status.Joining), up(3, Status.Leaving), up(4, Status.Down))

            owners(members).toSet() shouldBe setOf(up(1).node)
            Placement.owner("order", 0, listOf(up(2, Status.Joining))).shouldBeNull()
        }

        test("an override moves only its shard, and only to an Up member in the life it names") {
            val members = listOf(up(1), up(2), up(3, Status.Leaving))
            val hashed = owners(members)
            val shard = hashed.indexOfFirst { it != up(2).node }
            val to = { n: Int, uid: Long -> mapOf(shard to Incarnation(up(n).node, uid)) }
            val moved = { overrides: Map<Int, Incarnation> ->
                List(256) { Placement.owner("order", it, members, overrides[it]) }
            }

            moved(to(2, 2L)) shouldBe hashed.toMutableList().apply { set(shard, up(2).node) }
            withClue("a member that is not Up") { moved(to(3, 3L)) shouldBe hashed }
            withClue("another life at the same address") { moved(to(2, 7L)) shouldBe hashed }
        }

        test("workers spread as evenly as the members allow, and one member leaving moves few besides its own") {
            listOf(1 to 8, 3 to 8, 4 to 8, 5 to 16, 8 to 3).forAll { (members, count) ->
                val placed = Placement.spread("totals", count, (1..members).map { up(it) })
                val counts = placed.groupingBy { it }.eachCount().values
                withClue("$count over $members: $placed") { (counts.max() - counts.min()) shouldBeLessThanOrEqual 1 }
            }

            val four = Placement.spread("totals", 16, (1..4).map { up(it) })
            val three = Placement.spread("totals", 16, (1..3).map { up(it) })
            val gone = four.count { it == up(4).node }
            val moved = four.indices.count { four[it] != three[it] }
            withClue("$four then $three") { moved shouldBeLessThanOrEqual gone + 3 }
            Placement.spread("totals", 4, listOf(up(1, Status.Joining))) shouldBe List(4) { null }
        }
    }
}
