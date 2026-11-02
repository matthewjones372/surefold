package io.github.matthewjones372.surefold.cluster

import dev.surefold.actor.Behaviour
import dev.surefold.actor.remote.Codecs

/**
 * [count] actors named [name], worker 0 to worker `count - 1`, spread over the `Up` members as evenly as they go
 * (spec 0106): each runs on the member it hashes to among those short of their share, started as soon as it is
 * placed, with nothing sent to it, and moved by the same handoff as a singleton when that member leaves or is
 * downed, so it never runs on two members at once. Every node calls this with the same [name] and [count]. With a
 * [role], only members started with it run workers.
 *
 * What it is for is work with no messages to wait for, such as a read model's partitions, which would otherwise
 * pile up as singletons on the oldest member.
 */
fun <S, E> Cluster.spread(
    name: String,
    count: Int,
    role: String? = null,
    worker: (Int) -> Behaviour<Unit, S, E>,
) {
    require(count > 0) { "count must be positive, was $count" }
    val hosting = Hosting<Unit, Unit>(
        eager = true,
        owner = { k, members, _ -> Placement.spread(name, count, members.holding(role))[k] },
        start = { ctx, k -> ctx.spawn("$name-$k", worker(k)) },
        target = { actor, _ -> actor },
    )
    region(Sharding.path(name, prefix = "spread"), Codecs.unit, count, hosting, "spread-$name")
}
