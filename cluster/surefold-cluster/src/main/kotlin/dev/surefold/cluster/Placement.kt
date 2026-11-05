package dev.surefold.cluster

import dev.surefold.actor.remote.Node
import java.util.zip.CRC32

private const val FNV_OFFSET = -0x340d631b7bdddcdbL
private const val FNV_PRIME = 0x100000001b3L
private const val MIX_1 = -0x40a7b892e31b1a47L
private const val MIX_2 = -0x6b2fb644ecceee15L
private const val SHIFT_1 = 30
private const val SHIFT_2 = 27
private const val SHIFT_3 = 31
private const val BYTE = 0xff

/**
 * Where an entity lives, as every node works it out alike from the view it agrees on: an id's shard is a hash of the
 * id, and a shard's owner is the `Up` member that scores highest for it (rendezvous hashing). A member that joins or
 * leaves changes the owner only of the shards it wins or held. Every hash here is the same on every JVM.
 */
internal object Placement {

    fun shardOf(id: String, shards: Int): Int {
        val crc = CRC32().apply { update(id.toByteArray(Charsets.UTF_8)) }.value
        return Math.floorMod(crc, shards.toLong()).toInt()
    }

    /** The shard's owner: the member [moved] names if it is `Up` in that life (spec 0090), or else by hash. */
    fun owner(kind: String, shard: Int, members: Collection<Member>, moved: Incarnation? = null): Node? {
        val up = members.filter { it.status == Status.Up }
        if (moved != null && up.any { it.node == moved.node && it.uid == moved.uid }) return moved.node
        return up.maxWithOrNull(compareBy({ score(kind, shard, it.node) }, { it.node.toString() }))?.node
    }

    /**
     * Where each of [count] workers of [name] runs (spec 0106): worker by worker, the `Up` member that scores highest
     * for it among those still short of their share. Each member's share is `count / members`, and `count % members` of
     * them, whichever fill first, one more: so the workers are as even as they can be, and a member that joins or
     * leaves moves the workers it gains or held, and few others.
     */
    fun spread(name: String, count: Int, members: Collection<Member>): List<Node?> {
        val up = members.filter { it.status == Status.Up }.map { it.node }
        if (up.isEmpty()) return List(count) { null }
        val share = count / up.size
        var extra = count % up.size
        val held = mutableMapOf<Node, Int>()
        return List(count) { worker ->
            up.filter { (held[it] ?: 0) < share || ((held[it] ?: 0) == share && extra > 0) }
                .maxWith(compareBy({ score(name, worker, it) }, { it.toString() }))
                .also { chosen ->
                    val now = (held[chosen] ?: 0) + 1
                    if (now > share) extra--
                    held[chosen] = now
                }
        }
    }

    /** The oldest `Up` member, where a singleton runs: the lowest up-number, then the lowest address. */
    fun oldest(members: Collection<Member>): Node? =
        members
            .filter { it.status == Status.Up }
            .minWithOrNull(compareBy({ it.upNumber }, { it.node.toString() }))
            ?.node

    private fun score(kind: String, shard: Int, node: Node): Long = mix(fnv("$kind\u0000$shard\u0000$node"))

    private fun fnv(key: String): Long =
        key.toByteArray(Charsets.UTF_8).fold(FNV_OFFSET) { hash, byte ->
            (hash xor (byte.toLong() and BYTE.toLong())) * FNV_PRIME
        }

    /** SplitMix64's finaliser, so that keys differing in one character score far apart. */
    private fun mix(value: Long): Long {
        var z = (value xor (value ushr SHIFT_1)) * MIX_1
        z = (z xor (z ushr SHIFT_2)) * MIX_2
        return z xor (z ushr SHIFT_3)
    }
}
