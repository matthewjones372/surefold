package io.github.matthewjones372.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.withClue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private fun Net.upAll(vararg n: Int) {
    n.forEach { start(it, seeds(1, 2, 3)) }
    return until { n.all { view(it).up().size == n.size } } shouldBe true
}

/** Whether node [n] still takes part: its own view has it neither downed nor removed. */
private fun Net.active(n: Int) = view(n).members.firstOrNull { it.node == at(n) }?.status?.isLive == true

private fun Net.split(side: List<Int>, from: List<Int>) = side.forEach { isolate(it, from) }

private fun Net.members(n: Int) = view(n).members.map { it.node }.toSet()

/** A lease one holder has at a time, as a Kubernetes Lease or a DynamoDB item would be. */
private class OneHolder(var holder: String? = null) : Lease {
    override fun acquire(holder: String): Boolean {
        if (this.holder == null) this.holder = holder
        return this.holder == holder
    }
}

object DowningSuite : Suite() {
    init {
        test("a 3-2 partition leaves the three up and the two downed, with no moment where both sides are up") {
            val net = Net(downing = Downing.keepMajority(2.seconds))
            net.upAll(1, 2, 3, 4, 5)
            var overlap = false

            net.split(listOf(4, 5), from = listOf(1, 2, 3))

            var downedAt: Duration? = null
            var removedAt: Duration? = null

            net.until {
                val takenOver = (1..3).any { v -> at(4) !in net.members(v) || at(5) !in net.members(v) }
                if (takenOver && (net.active(4) || net.active(5))) overlap = true
                if (downedAt == null && net.view(1).members.any { it.status == Status.Down }) downedAt = net.now
                if (removedAt == null && at(4) !in net.members(1)) removedAt = net.now
                (1..3).all { net.members(it) == setOf(at(1), at(2), at(3)) }
            } shouldBe true
            withClue("the three removed the two while one of the two still took part") { overlap shouldBe false }
            withClue("the three removed the two sooner than stableAfter after downing them") {
                (checkNotNull(removedAt) - checkNotNull(downedAt) >= 2.seconds) shouldBe true
            }
            net.active(4) shouldBe false
            net.active(5) shouldBe false
            (1..3).forAll { net.view(it).up().toSet() shouldBe setOf(at(1), at(2), at(3)) }
        }

        test("a node unreachable for less than stableAfter is downed by nobody") {
            val net = Net(downing = Downing.keepMajority(5.seconds))
            net.upAll(1, 2, 3, 4, 5)

            net.isolate(5, listOf(1, 2, 3, 4))
            net.until { at(5) in net.view(1).unreachable } shouldBe true
            val _ = net.until(steps = 10) { false }
            net.rejoin(5, listOf(1, 2, 3, 4))

            val anyDowned = net.until(steps = 60) {
                (1..5).any { v -> net.view(v).members.any { it.status == Status.Down } }
            }
            anyDowned shouldBe false
            (1..5).forAll { net.view(it).up().size shouldBe 5 }
        }

        /*
         * Each suspects the other while their gossip still flows, so each learns that it is suspected too. The side
         * that stays is the one with the lowest address, and it must not count itself among the unreachable it downs.
         */
        test("two members that stop hearing each other's acks keep the lowest up rather than downing both") {
            val net = Net(downing = Downing.keepMajority(2.seconds))
            net.upAll(1, 2)

            net.dropAcks(1, 2)

            net.until { !net.active(2) } shouldBe true
            net.active(1) shouldBe true
        }

        test("an even split keeps the side with the lowest address") {
            val net = Net(downing = Downing.keepMajority(2.seconds))
            net.upAll(1, 2, 3, 4)

            net.split(listOf(3, 4), from = listOf(1, 2))

            val kept = net.until { net.members(1) == setOf(at(1), at(2)) && !net.active(3) && !net.active(4) }
            kept shouldBe true
            net.active(1) shouldBe true
            net.active(2) shouldBe true
        }

        test("a static quorum keeps no side smaller than it, even when that is every side") {
            val net = Net(downing = Downing.staticQuorum(3, stableAfter = 2.seconds))
            net.upAll(1, 2, 3, 4)

            net.split(listOf(3, 4), from = listOf(1, 2))

            net.until { (1..4).none { net.active(it) } } shouldBe true
        }

        test("a lease keeps the side whose lowest member holds it, even the smaller one") {
            val lease = OneHolder(holder = at(4).toString())
            val net = Net(downing = Downing.lease(lease, stableAfter = 2.seconds))
            net.upAll(1, 2, 3, 4, 5)

            net.split(listOf(4, 5), from = listOf(1, 2, 3))

            val kept = net.until { (1..3).none { net.active(it) } && net.members(4) == setOf(at(4), at(5)) }
            kept shouldBe true
            net.active(4) shouldBe true
            net.active(5) shouldBe true
        }
    }
}
