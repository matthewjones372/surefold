package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.remote.Node
import java.util.zip.CRC32

/** One life of a node: a node that restarts is a new incarnation at the same address. */
/** One life of a node: its address, a number no other life of it has, and the roles it was started with. */
internal data class Incarnation(val node: Node, val uid: Long, val roles: Set<String> = emptySet())

internal data class Entry(val status: Status, val upNumber: Int)

/** Whether [by] can reach [of]: only [by] writes it, each time with a higher version. */
internal data class Observation(val by: Incarnation, val of: Incarnation)

internal data class Seen(val reachable: Boolean, val version: Long)

/** What a member last said the membership was, as a hash, so that a view is agreed once every member says the same. */
internal data class Digest(val version: Long, val hash: Long)

/** What one shard cost its owner (spec 0090): the entities it runs now, and the messages of the last interval. */
internal data class ShardLoad(val entities: Int, val messages: Int)

/** What a member last said it runs, per kind that rebalances and per shard with any load; only it writes it. */
internal data class Load(val version: Long, val kinds: Map<String, Map<Int, ShardLoad>>)

/** The shards of one kind the leader has moved off their hash owner, and to whom; each leader raises the version. */
internal data class Moves(val version: Long, val to: Map<Int, Incarnation>) {
    /** The later of two, or on the same version, one picked alike on every node. */
    fun latest(other: Moves): Moves = maxOf(this, other, compareBy({ it.version }, { it.to.toSortedMap().toString() }))
}

/**
 * What nodes tell each other. Every part merges by a rule that ignores order and repeats: a member's status moves
 * only forward, and an observation or a digest is its writer's latest. [origin] is the node that formed the cluster,
 * so two clusters never merge.
 */
internal data class Gossip(
    val origin: Incarnation?,
    val members: Map<Incarnation, Entry>,
    val observed: Map<Observation, Seen>,
    val digests: Map<Incarnation, Digest>,
    val loads: Map<Incarnation, Load> = emptyMap(),
    val moves: Map<String, Moves> = emptyMap(),
    /** The lives that have said they are ready to be `Up` (spec 0129): a life is never unready again. */
    val admitted: Set<Incarnation> = emptySet(),
) {
    fun merge(other: Gossip): Gossip = copy(
        members = union(members, other.members) { a, b ->
            Entry(maxOf(a.status, b.status), maxOf(a.upNumber, b.upNumber))
        },
        observed = union(observed, other.observed) { a, b -> if (b.version > a.version) b else a },
        digests = union(digests, other.digests) { a, b -> if (b.version > a.version) b else a },
        loads = union(loads, other.loads) { a, b -> if (b.version > a.version) b else a },
        moves = union(moves, other.moves, Moves::latest),
        admitted = admitted + other.admitted,
    )

    /** The live members that a live member other than themselves last observed as unreachable. */
    fun unreachable(): Set<Incarnation> {
        val live = live()
        return observed.filter { (seen, record) -> !record.reachable && seen.by in live && seen.of in live }
            .keys.mapTo(mutableSetOf()) { it.of }
    }

    fun live(): Set<Incarnation> = members.filterValues { it.status.isLive }.keys

    /**
     * A hash of [members] and [moves] that is the same on every JVM, as an enum's own hash is not. It leaves out the
     * load, which changes too often to agree on, and adds nothing for a kind with no moves. It leaves out [admitted]
     * too: only the leader reads it, the whole gossip rides every probe, and a member it lets in changes [members].
     */
    fun hash(): Long = CRC32().apply {
        val moved = moves.flatMap { (kind, m) -> m.to.map { (shard, to) -> "$kind/$shard>${to.node}#${to.uid};" } }
        (members.entries.map { (m, e) -> "${m.node}#${m.uid}=${e.status.name}/${e.upNumber};" } + moved).sorted()
            .forEach { update(it.toByteArray(Charsets.UTF_8)) }
    }.value

    companion object {
        val None = Gossip(null, emptyMap(), emptyMap(), emptyMap())
    }
}

private fun <K, V : Any> union(a: Map<K, V>, b: Map<K, V>, pick: (V, V) -> V): Map<K, V> =
    a.toMutableMap().apply { b.forEach { (key, value) -> merge(key, value, pick) } }

/** What one node says to another. A `to` names one life, so a node that has restarted ignores what was for the last. */
internal sealed interface Swim {
    data class Join(val from: Incarnation) : Swim

    data class Welcome(val to: Incarnation, val gossip: Gossip) : Swim

    data class Ping(val from: Incarnation, val to: Incarnation, val seq: Long, val gossip: Gossip) : Swim

    /** [from] answered probe [seq]; a helper passes it on to the node that asked, with the same [from]. */
    data class Ack(val from: Incarnation, val seq: Long, val gossip: Gossip) : Swim

    data class PingReq(val from: Incarnation, val target: Incarnation, val seq: Long, val gossip: Gossip) : Swim
}

internal data class Send(val to: Node, val message: Swim)
