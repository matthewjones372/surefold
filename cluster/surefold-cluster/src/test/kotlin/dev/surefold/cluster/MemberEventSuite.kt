package dev.surefold.cluster

import dev.surefold.actor.remote.Node
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBeEmpty
import dev.surefold.junit.shouldContainExactly

private fun member(n: Int, status: Status = Status.Up, uid: Long = n.toLong()) =
    Member(Node("n$n", "10.0.0.$n", 25520), uid, status, n)

private fun view(vararg members: Member, unreachable: Set<Int> = emptySet()) =
    View(members.toList(), unreachable.mapTo(mutableSetOf()) { member(it).node }, members.firstOrNull()?.node)

object MemberEventSuite : Suite() {
    init {
        test("a member moved to Up is said to be Up, and a joiner is not") {
            val before = view(member(1), member(2, Status.Joining))
            val after = view(member(1), member(2), member(3, Status.Joining))

            changes(before, after) shouldContainExactly listOf(MemberEvent.Up(member(2)))
        }

        test("a member that becomes unreachable is said to be, and again once it is reachable") {
            val all = arrayOf(member(1), member(2), member(3))

            changes(view(*all), view(*all, unreachable = setOf(3))) shouldContainExactly
                listOf(MemberEvent.Unreachable(member(3)))
            changes(view(*all, unreachable = setOf(3)), view(*all)) shouldContainExactly
                listOf(MemberEvent.Reachable(member(3)))
        }

        test("a member that is gone from the view is removed, and not said to be reachable on its way out") {
            changes(view(member(1), member(2), unreachable = setOf(2)), view(member(1))) shouldContainExactly
                listOf(MemberEvent.Removed(member(2)))
        }

        test("a node that restarts is a removed member and a new one Up, not a member that stayed") {
            changes(view(member(1), member(2)), view(member(1), member(2, uid = 22))) shouldContainExactly
                listOf(MemberEvent.Up(member(2, uid = 22)), MemberEvent.Removed(member(2)))
        }

        test("a member downed is said to be, once") {
            val downed = view(member(1), member(2, Status.Down))

            changes(view(member(1), member(2)), downed) shouldContainExactly
                listOf(MemberEvent.Downed(member(2, Status.Down)))
            changes(downed, downed).shouldBeEmpty()
        }

        test("a view that has not changed says nothing") {
            changes(view(member(1), member(2)), view(member(1), member(2))).shouldBeEmpty()
        }
    }
}
