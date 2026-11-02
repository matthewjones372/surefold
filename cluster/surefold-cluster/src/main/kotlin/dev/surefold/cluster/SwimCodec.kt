package dev.surefold.cluster

import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut

private const val JOIN = 1
private const val WELCOME = 2
private const val PING = 3
private const val ACK = 4
private const val PING_REQ = 5

/** The protocol as it crosses between nodes. */
internal object SwimCodec : MessageCodec<Swim> {
    override fun write(message: Swim, out: WireOut) = when (message) {
        is Swim.Join -> {
            out.int(JOIN)
            out.incarnation(message.from)
        }

        is Swim.Welcome -> {
            out.int(WELCOME)
            out.incarnation(message.to)
            out.gossip(message.gossip)
        }

        is Swim.Ping -> {
            out.int(PING)
            out.incarnation(message.from)
            out.incarnation(message.to)
            out.long(message.seq)
            out.gossip(message.gossip)
        }

        is Swim.Ack -> {
            out.int(ACK)
            out.incarnation(message.from)
            out.long(message.seq)
            out.gossip(message.gossip)
        }

        is Swim.PingReq -> {
            out.int(PING_REQ)
            out.incarnation(message.from)
            out.incarnation(message.target)
            out.long(message.seq)
            out.gossip(message.gossip)
        }
    }

    override fun read(input: WireIn): Swim = when (val tag = input.int()) {
        JOIN -> Swim.Join(input.incarnation())
        WELCOME -> Swim.Welcome(input.incarnation(), input.gossip())
        PING -> Swim.Ping(input.incarnation(), input.incarnation(), input.long(), input.gossip())
        ACK -> Swim.Ack(input.incarnation(), input.long(), input.gossip())
        PING_REQ -> Swim.PingReq(input.incarnation(), input.incarnation(), input.long(), input.gossip())
        else -> error("no cluster message has the tag $tag")
    }
}

private fun WireOut.incarnation(of: Incarnation) {
    string(of.node.toString())
    long(of.uid)
    int(of.roles.size)
    of.roles.sorted().forEach(::string)
}

private fun WireIn.incarnation() = Incarnation(Node.parse(string()), long(), List(int()) { string() }.toSet())

private fun <K, V> WireOut.map(map: Map<K, V>, write: (K, V) -> Unit) {
    int(map.size)
    map.forEach { (key, value) -> write(key, value) }
}

private fun <K, V> WireIn.map(read: () -> Pair<K, V>): Map<K, V> = List(int()) { read() }.toMap()

private fun WireOut.gossip(gossip: Gossip) {
    nullable(gossip.origin) { incarnation(it) }
    map(gossip.members) { member, entry ->
        incarnation(member)
        int(entry.status.ordinal)
        int(entry.upNumber)
    }
    map(gossip.observed) { observation, seen ->
        incarnation(observation.by)
        incarnation(observation.of)
        boolean(seen.reachable)
        long(seen.version)
    }
    map(gossip.digests) { member, digest ->
        incarnation(member)
        long(digest.version)
        long(digest.hash)
    }
    map(gossip.loads) { member, load ->
        incarnation(member)
        long(load.version)
        map(load.kinds) { kind, shards ->
            string(kind)
            map(shards) { shard, cost ->
                int(shard)
                int(cost.entities)
                int(cost.messages)
            }
        }
    }
    map(gossip.moves) { kind, moves ->
        string(kind)
        long(moves.version)
        map(moves.to) { shard, to ->
            int(shard)
            incarnation(to)
        }
    }
    int(gossip.admitted.size)
    gossip.admitted.forEach { incarnation(it) }
}

private fun WireIn.gossip() = Gossip(
    origin = nullable { incarnation() },
    members = map { incarnation() to Entry(Status.entries[int()], int()) },
    observed = map { Observation(incarnation(), incarnation()) to Seen(boolean(), long()) },
    digests = map { incarnation() to Digest(long(), long()) },
    loads = map { incarnation() to Load(long(), map { string() to map { int() to ShardLoad(int(), int()) } }) },
    moves = map { string() to Moves(long(), map { int() to incarnation() }) },
    admitted = List(int()) { incarnation() }.toSet(),
)
