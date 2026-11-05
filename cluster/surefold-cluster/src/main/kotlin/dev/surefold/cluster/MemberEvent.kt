package dev.surefold.cluster

/** A change in the view one node has, told to its subscribers in the order the node saw them. */
sealed interface MemberEvent {
    val member: Member

    data class Up(override val member: Member) : MemberEvent

    /** Some member cannot reach [member]: the cluster moves no one on until it is reachable or downed. */
    data class Unreachable(override val member: Member) : MemberEvent

    data class Reachable(override val member: Member) : MemberEvent

    /** [member] is out: the side that stays downed it, or it downed itself on the side that does not. */
    data class Downed(override val member: Member) : MemberEvent

    /** [member] has left the view, and every watch on its actors has ended. */
    data class Removed(override val member: Member) : MemberEvent
}

/** What changed from [before] to [after]; a node that restarted is its earlier life removed and a new member. */
internal fun changes(before: View, after: View): List<MemberEvent> {
    fun Member.life() = node to uid
    val was = before.members.associateBy { it.life() }
    val now = after.members.associateBy { it.life() }
    val up = after.members.filter { it.status == Status.Up && was[it.life()]?.status != Status.Up }
    val unreachable = after.members.filter { it.node in after.unreachable && it.node !in before.unreachable }
    val reachable = after.members.filter { it.node !in after.unreachable && it.node in before.unreachable }
    val downed = after.members.filter { it.status == Status.Down && was[it.life()]?.status != Status.Down }
    val removed = before.members.filter { it.life() !in now }
    return up.map(MemberEvent::Up) +
        unreachable.map(MemberEvent::Unreachable) +
        reachable.map(MemberEvent::Reachable) +
        downed.map(MemberEvent::Downed) +
        removed.map(MemberEvent::Removed)
}
