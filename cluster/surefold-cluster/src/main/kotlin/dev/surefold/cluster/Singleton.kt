package dev.surefold.cluster

import dev.surefold.actor.ActorRef
import dev.surefold.actor.Behaviour
import dev.surefold.actor.remote.MessageCodec

/**
 * One actor named [name] in the whole cluster, made by [behaviour] on the oldest `Up` member and started there as
 * soon as no other member runs it. When that member leaves or is downed, the next oldest starts it once the old one
 * has stopped it or has left the view, by the same handoff as a shard. The ref reaches whichever member runs it now;
 * every node calls this with the same [name] and [codec]. With a [role], it runs on the oldest `Up` member started with
 * it (spec 0083).
 */
fun <M : Any, S, E> Cluster.singleton(
    name: String,
    codec: MessageCodec<M>,
    role: String? = null,
    behaviour: () -> Behaviour<M, S, E>,
): ActorRef<M> {
    val hosting = Hosting<M, M>(
        eager = true,
        owner = { _, members, _ -> Placement.oldest(members.holding(role)) },
        start = { ctx, _ -> ctx.spawn(name, behaviour()) },
        target = { actor, _ -> actor },
    )
    return ShardedRef(region(Sharding.path(name, prefix = "singleton"), codec, 1, hosting, "singleton-$name"), name)
}
