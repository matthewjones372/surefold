package dev.surefold.cluster

import dev.surefold.actor.remote.Node
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe

object OutOfClusterSuite : Suite() {
    private val self = Node("a", "127.0.0.1", 1)
    private val other = Node("b", "127.0.0.1", 2)

    private fun view(mine: Status, theirs: Status?) =
        View(
            listOfNotNull(Member(self, 1, mine, 1), theirs?.let { Member(other, 2, it, 2) }),
            emptySet(),
            null,
        )

    init {
        test(
            "a leaving node is out once removed or downed, or once no other member is Up to take its shards",
        ) {
            view(Status.Leaving, Status.Up).outFor(self) shouldBe false
            view(Status.Leaving, null).outFor(self) shouldBe true
            view(Status.Down, Status.Up).outFor(self) shouldBe true
            view(Status.Leaving, Status.Down).outFor(self) shouldBe true
            view(Status.Leaving, Status.Joining).outFor(self) shouldBe true
            view(Status.Leaving, Status.Leaving).outFor(self) shouldBe true
        }

        test("a node that downed itself measures itself alone, Down, with no leader and nothing unreachable") {
            val cut = View(listOf(Member(self, 1, Status.Down, 1), Member(other, 2, Status.Up, 2)), setOf(other), other)

            val alone = View(listOf(Member(self, 1, Status.Down, 1)), emptySet(), null)
            cut.measuredBy(self, downed = true) shouldBe alone
            cut.measuredBy(self, downed = false) shouldBe cut
        }
    }
}
