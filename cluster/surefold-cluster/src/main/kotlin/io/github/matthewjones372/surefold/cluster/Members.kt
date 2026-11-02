package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** Where a member is in its life. Each only moves forward, which is what lets two views merge without asking. */
enum class Status {
    Joining,
    Up,
    Leaving,
    Down,
    Removed,
    ;

    internal val isLive: Boolean get() = this == Joining || this == Up || this == Leaving
}

/** One member as the agreed view has it. [upNumber] orders members by when they came up: lower is older. */
data class Member(
    val node: Node,
    val uid: Long,
    val status: Status,
    val upNumber: Int,
    /** What the node was started to do (spec 0083): sharding and singletons may be placed on members by role. */
    val roles: Set<String> = emptySet(),
)

/**
 * The membership as one node sees it: every member not yet removed, oldest first and joiners last, the nodes some
 * member cannot reach, and the oldest reachable member, which moves joiners to `Up`.
 */
data class View(val members: List<Member>, val unreachable: Set<Node>, val leader: Node?) {
    internal companion object {
        val None = View(emptyList(), emptySet(), null)
    }
}

/**
 * How a node probes and joins. Each round it probes one member; one that has not answered within [ackWithin] is
 * probed by [helpers] others for it, and one nobody answered for by the end of the round is unreachable. A node that
 * is the lowest of its seeds forms the cluster once no other seed has let it join for [formAfter].
 */
data class Gossiping(
    val probeEvery: Duration = 1.seconds,
    val ackWithin: Duration = 300.milliseconds,
    val helpers: Int = 3,
    val formAfter: Duration = 5.seconds,
) {
    init {
        require(ackWithin < probeEvery) { "ackWithin ($ackWithin) must be shorter than probeEvery ($probeEvery)" }
    }
}
