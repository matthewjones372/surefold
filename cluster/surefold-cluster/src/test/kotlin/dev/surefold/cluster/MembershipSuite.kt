package dev.surefold.cluster

import dev.surefold.junit.Suite
import dev.surefold.junit.forAll
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import kotlin.time.Duration.Companion.seconds

private val five = listOf(1, 2, 3, 4, 5)

private fun Net.fiveUp() {
    five.forEach { start(it, seeds(1, 2, 3)) }
    return withClue("five nodes agree on five Up") {
        until { five.all { view(it).up().size == 5 } && five.map(::view).distinct().size == 1 } shouldBe true
    }
}

object MembershipSuite : Suite() {
    init {
        test(
            "five nodes started from the same seeds agree on one view, with all five Up and the lowest seed oldest",
        ) {
            val net = Net()
            net.fiveUp()

            val view = net.view(3)
            view.members.map { it.node }.toSet() shouldBe five.map(::at).toSet()
            view.members.first().node shouldBe at(1)
            view.leader shouldBe at(1)
            view.unreachable.shouldBeEmpty()
        }

        test("a stopped node is seen unreachable by every other, and stays a member") {
            val net = Net()
            net.fiveUp()

            net.stop(5)

            net.until { (1..4).all { at(5) in net.view(it).unreachable } } shouldBe true
            (1..4).forAll { net.view(it).up() shouldContainExactly net.view(1).up() }
            net.view(1).up().size shouldBe 5
        }

        test("a node one other cannot reach is still reachable, through the others probing it for that one") {
            val net = Net()
            net.fiveUp()

            net.cut(1, 2)

            net.until(steps = 100) { five.any { net.view(it).unreachable.isNotEmpty() } } shouldBe false
        }

        test("a node cut off from all is unreachable, and reachable again once its links heal") {
            val net = Net()
            net.fiveUp()

            net.isolate(4, listOf(1, 2, 3, 5))
            net.until { listOf(1, 2, 3, 5).all { at(4) in net.view(it).unreachable } } shouldBe true
            net.rejoin(4, listOf(1, 2, 3, 5))

            net.until { five.all { net.view(it).unreachable.isEmpty() } } shouldBe true
        }

        test("a joiner is not moved to Up while a member is unreachable, and is once it is back") {
            val net = Net()
            net.fiveUp()
            net.isolate(5, listOf(1, 2, 3, 4))
            net.until { at(5) in net.view(1).unreachable } shouldBe true

            val _ = net.start(6, seeds(1))

            net.until(steps = 50) { at(6) in net.view(1).up() } shouldBe false
            net.view(1).members.first { it.node == at(6) }.status shouldBe Status.Joining
            net.rejoin(5, listOf(1, 2, 3, 4))
            net.until { (1..6).all { at(6) in net.view(it).up() } } shouldBe true
        }

        test(
            "a joiner that waits to be told is Joining in every view until it is ready, and Up in every view after",
        ) {
            val net = Net()
            net.fiveUp()

            val waiting = net.start(6, seeds(1), ready = false)

            net.until { (1..6).all { at(6) in net.view(it).members.map(Member::node) } } shouldBe true
            net.until(steps = 50) { (1..6).any { at(6) in net.view(it).up() } } shouldBe false
            (1..6).forAll { net.view(it).members.first { m -> m.node == at(6) }.status shouldBe Status.Joining }
            waiting.markReady()
            net.until { (1..6).all { at(6) in net.view(it).up() } } shouldBe true
        }

        test(
            "a node that forms the cluster waiting is not Up until it is ready, and leads its joiners meanwhile",
        ) {
            val net = Net()
            val first = net.start(1, seeds(1, 2), ready = false)
            val _ = net.start(2, seeds(1, 2))

            net.until { at(2) in net.view(2).up() } shouldBe true
            net.view(2).members.first { it.node == at(1) }.status shouldBe Status.Joining
            first.markReady()
            net.until { (1..2).all { net.view(it).up().toSet() == setOf(at(1), at(2)) } } shouldBe true
        }

        test("a node that restarts joins as a new member, and its earlier life is downed and removed") {
            val net = Net(downing = Downing.keepMajority(2.seconds))
            net.fiveUp()

            val _ = net.start(5, seeds(1), uid = 55)

            val laterUp = net.until {
                (1..5).all { v -> net.view(v).members.any { it.uid == 55L && it.status == Status.Up } }
            }
            laterUp shouldBe true
            net.until { net.view(1).members.none { it.node == at(5) && it.uid == 5L } } shouldBe true
        }

        test("a member that leaves is removed from every view") {
            val net = Net()
            net.fiveUp()

            net.nodes.getValue(at(2)).leave()

            val removed = net.until { listOf(1, 3, 4, 5).all { v -> net.view(v).members.none { it.node == at(2) } } }
            removed shouldBe true
        }

        test("a member that goes quiet once it is seen leaving is removed without waiting to be downed") {
            val net = Net()
            net.fiveUp()

            net.nodes.getValue(at(2)).leave()
            net.until { net.view(3).members.any { it.node == at(2) && it.status == Status.Leaving } } shouldBe true
            net.stop(2)
            withClue("the leader has not removed it yet") { net.view(1).members.any { it.node == at(2) } shouldBe true }
            // A second leaver changes the view the first last agreed to, so every member can never agree with it.
            net.nodes.getValue(at(4)).leave()

            // Well short of the 20 s keepMajority waits before it downs an unreachable member.
            val removed = net.until(steps = 50) {
                listOf(1, 3, 5).all { v -> net.view(v).members.none { it.node == at(2) || it.node == at(4) } }
            }
            removed shouldBe true
        }

        test("a member that goes the moment it is out first tells the others it is leaving") {
            val net = Net()
            val three = listOf(1, 2, 3)
            val members = three.map { net.start(it, seeds(1, 2, 3)) }
            net.until { three.all { net.view(it).up().size == 3 } } shouldBe true

            // Node 1 hears the other two leaving, then leaves itself and is out at once, with no other member Up.
            listOf(2, 3).forEach { members[it - 1].leave() }
            net.until { net.view(1).members.count { it.status == Status.Leaving } == 2 } shouldBe true
            members[0].leave()
            net.goWhenOut(1)
            net.stopped(1) shouldBe true

            // Well short of the 20 s keepMajority waits before it downs a member that went without a word.
            val out = net.until(steps = 50) { listOf(2, 3).onEach(net::goWhenOut).all(net::stopped) }
            out shouldBe true
        }

        test("nodes of another cluster that probe this one are not taken in") {
            val net = Net()
            (1..3).forEach { net.start(it, seeds(1)) }
            (7..8).forEach { net.start(it, seeds(7)) }
            net.until { net.view(3).up().size == 3 && net.view(8).up().size == 2 } shouldBe true

            // Node 8 learns of node 2, as a misconfigured seed list would have it, and asks to join through it.
            val _ = net.nodes.getValue(at(8)).receive(Swim.Join(Incarnation(at(2), 2)), net.now)
            val _ = net.until(steps = 30) { false }

            net.view(1).up() shouldContainExactly listOf(at(1), at(2), at(3))
            net.view(7).up() shouldContainExactly listOf(at(7), at(8))
        }

        test("a node that is not the lowest seed waits for one to form the cluster, and never forms one itself") {
            val net = Net()
            val _ = net.start(2, seeds(1, 2))

            net.until(steps = 100) { net.view(2).members.isNotEmpty() } shouldBe false
            val _ = net.start(1, seeds(1, 2))
            net.until { net.view(2).up() == listOf(at(1), at(2)) } shouldBe true
        }

        test("an Up member that goes before its joiners hear them Up leaves no cluster without a leader") {
            val net = Net(downing = Downing.keepMajority(2.seconds))
            val _ = net.start(1, seeds(1))
            net.until { net.view(1).up() == listOf(at(1)) } shouldBe true
            val _ = net.start(2, seeds(1))
            val _ = net.start(3, seeds(1))

            // Stopped in the step it moves them on, before any gossip saying so has left it.
            net.until { net.view(1).up().size == 3 } shouldBe true
            net.stop(1)
            withClue("the joiners still see only the member that went Up") {
                net.view(2).up() shouldBe listOf(at(1))
            }

            net.until { listOf(2, 3).all { net.view(it).up().toSet() == setOf(at(2), at(3)) } } shouldBe true
            net.view(2).members.first { it.node == at(1) }.status shouldBe Status.Down
        }

        test(
            "the lowest seed restarted while its welcome is slow joins the cluster it hears, and forms no second one",
        ) {
            val net = Net()
            net.fiveUp()
            // The earlier life is gone as the new one starts at its address.
            val _ = net.start(1, seeds(1, 2, 3), uid = 11)
            net.loseJoinsFrom(1)

            // Well past formAfter, while the others still probe the earlier life at its address.
            val _ = net.until(steps = 100) { false }
            withClue("a restarted seed that heard the cluster does not form its own") {
                net.view(1).members.none { it.uid == 11L && it.status == Status.Up } shouldBe true
            }

            net.hearJoinsFrom(1)
            val rejoined = net.until {
                (1..5).all { v -> net.view(v).members.any { it.uid == 11L && it.status == Status.Up } }
            }
            rejoined shouldBe true
            (1..5).map { net.view(it).members.count { m -> m.status == Status.Up } }.distinct() shouldBe listOf(5)
        }
    }
}
