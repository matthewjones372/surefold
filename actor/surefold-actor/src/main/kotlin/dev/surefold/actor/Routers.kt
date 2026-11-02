package dev.surefold.actor

import dev.surefold.Schedule
import java.util.concurrent.atomic.AtomicLong

/** Which of [of] routees takes [message], as an index from 0. */
fun interface Route<in M : Any> {
    fun pick(message: M, of: Int): Int
}

/** Each routee in turn. */
fun roundRobin(): Route<Any> {
    val next = AtomicLong()
    return Route { _, of -> Math.floorMod(next.getAndIncrement(), of.toLong()).toInt() }
}

/** The routee [key] hashes to, so one key always reaches one routee while the routees stay the same. */
fun <M : Any> hashing(key: (M) -> Any?): Route<M> = Route { message, of -> Math.floorMod(key(message).hashCode(), of) }

/**
 * A router: [size] routees, each from [routee], spawned as its children on its first message, and each message told
 * to the one [route] picks. A routee whose mailbox is full is passed over for the next, and a turn whose every routee
 * is full fails. [restart] applies to each routee on its own, so one failing restarts without the others; one that
 * stops for good leaves the pool smaller, and a pool with none left stops.
 */
fun <M : Any, S, E> pool(
    size: Int,
    route: Route<M> = roundRobin(),
    restart: Schedule<Failure<E>, *>? = null,
    routee: () -> Behaviour<M, S, E>,
): Behaviour<M, List<ActorRef<M>>?, Nothing> {
    require(size > 0) { "a pool needs at least one routee, was $size" }
    return behaviour<M, List<ActorRef<M>>?>(null) { ctx, routees, message ->
        val live = routees ?: List(size) { ctx.spawn("routee-${it + 1}", routee(), restart).also(ctx::watch) }
        val first = route.pick(message, live.size)
        check(live.indices.any { live[(first + it) % live.size].offer(message) }) {
            "every routee of ${ctx.self.address.path} is full"
        }
        if (routees == null) become(live) else stay()
    }.onSignal { _, routees, signal ->
        when (signal) {
            Signal.Stopping -> stay()

            is Signal.Terminated -> {
                val left = routees.orEmpty().filterNot { it == signal.ref }
                if (left.isEmpty()) stop() else become(left)
            }
        }
    }
}

/** Refs already running, as one: a tell picks one by [route] on the caller's thread, with no actor between. */
fun <M : Any> group(refs: List<ActorRef<M>>, route: Route<M> = roundRobin()): ActorRef<M> {
    require(refs.isNotEmpty()) { "a group needs at least one ref" }
    return Group(refs.toList(), route)
}

fun <M : Any> group(vararg refs: ActorRef<M>): ActorRef<M> = group(refs.toList())

private val groups = AtomicLong()

private class Group<M : Any>(private val refs: List<ActorRef<M>>, private val route: Route<M>) : ActorRef<M> {
    override val address = Address(refs.first().address.node, "/group", groups.incrementAndGet())

    override fun tell(message: M) = refs[route.pick(message, refs.size)].tell(message)

    override fun toString(): String = "Group($refs)"
}
