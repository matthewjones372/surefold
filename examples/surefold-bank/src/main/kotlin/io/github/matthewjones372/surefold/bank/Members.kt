package io.github.matthewjones372.surefold.bank

import dev.surefold.Shared
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.stay
import dev.surefold.timeoutOrNull
import io.github.matthewjones372.surefold.cluster.Member
import io.github.matthewjones372.surefold.cluster.MemberEvent
import io.github.matthewjones372.surefold.cluster.Status
import kotlin.time.Duration

/** The members one node has heard of, by their events: who is in, and who some member cannot reach. */
internal data class Seen(val members: Map<Node, Member> = emptyMap(), val unreachable: Set<Node> = emptySet()) {
    fun after(event: MemberEvent): Seen = when (event) {
        is MemberEvent.Up, is MemberEvent.Downed -> copy(members = members + (event.member.node to event.member))
        is MemberEvent.Unreachable -> copy(unreachable = unreachable + event.member.node)
        is MemberEvent.Reachable -> copy(unreachable = unreachable - event.member.node)
        is MemberEvent.Removed -> Seen(members - event.member.node, unreachable - event.member.node)
    }

    val up: List<Member> get() = members.values.filter { it.status == Status.Up }.sortedBy { it.upNumber }
}

/**
 * What a node's subscriber to its cluster has seen, for code outside the actors to wait on. `Cluster.await` is the
 * library's own and internal, so the bank keeps its own copy of the view, built from the events it is told.
 */
internal class Members {
    private val view = Shared(Seen(), name = "bank.members")

    val seen: Seen get() = view.peek()

    /** The subscriber: it keeps [seen] and hands each event, with what it made of it, to [heard]. */
    fun subscriber(heard: (MemberEvent, Seen) -> Unit) = behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
        val next = view.modify { was -> was.after(event).let { it to it } }
        heard(event, next)
        stay()
    }

    /** Waits up to [within] until what has been seen satisfies [until]; whether it did. */
    fun await(within: Duration, until: (Seen) -> Boolean): Boolean =
        timeoutOrNull(within) { view.awaitUntil(until) } != null
}
