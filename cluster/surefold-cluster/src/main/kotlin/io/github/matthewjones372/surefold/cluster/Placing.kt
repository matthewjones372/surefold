package io.github.matthewjones372.surefold.cluster

import dev.surefold.Carriers
import dev.surefold.Gauge
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Ctx
import dev.surefold.actor.DeadLetter
import dev.surefold.actor.HandOn
import dev.surefold.actor.PlumbingSeam
import dev.surefold.actor.deadLetter
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node

/**
 * What a region runs its shards with: which member owns a shard, what runs one here, and how a message reaches what
 * runs it. [eager] starts a shard's host as soon as the shard is free here, rather than on its first message.
 * [target] is what a message for an id is told to, through its host.
 */
internal class Hosting<M : Any, H : Any>(
    val eager: Boolean,
    val owner: (shard: Int, members: List<Member>, moved: Map<Int, Incarnation>) -> Node?,
    val start: (Ctx<Region<M>>, shard: Int) -> ActorRef<H>,
    val target: (host: ActorRef<H>, id: String) -> ActorRef<M>,
)

/** What a region gauges (spec 0081): the shards it may run now, and what it keeps for a shard with no owner yet. */
internal class RegionMeters(val shards: Gauge, val buffered: Gauge)

/** An envelope kept for a shard with no owner yet, with what its handling carried (spec 0122). */
private class Kept<M : Any>(val envelope: Region.Envelope<M>, val carried: Map<String, String>)

/** The key of a region's retry timer: one at a time, however many messages ran out of hops. */
private object RetryKey

/** The key of a region's timer that hands busy hosts what it kept for them. */
private object DrainKey

/** The key of a region's timer that asks again for the shards it waits on. */
private object AskAgainKey

/**
 * One region's work: each message to the entity here, or to the region of the node that owns its shard; and the
 * handoff that keeps an entity from running on two nodes at once.
 *
 * A node that wins a shard asks every other member that could hold it, `Up` or `Leaving`, to release it, and runs
 * none of the shard until each has, or has left the view. A member releases a shard only once it neither runs any of
 * it nor believes it owns it, so two nodes whose views disagree both wait until the views agree. What arrives for a
 * shard meanwhile is kept, up to a bound. The ask is repeated while any member has yet to answer it: a member whose
 * region did not exist yet when it was asked drops the ask, and would otherwise never answer.
 */
internal class Placing<M : Any, H : Any>(
    private val cluster: Cluster,
    private val shards: Int,
    private val wire: MessageCodec<Region<M>>,
    private val path: String,
    private val hosting: Hosting<M, H>,
    private val meters: RegionMeters,
) {
    private val self = cluster.self
    private var view = View.None
    private var moved = emptyMap<Int, Incarnation>()

    // Shards this node may run now; the managers of those it has run since; and those it has let go, until they end.
    private val ready = HashSet<Int>()
    private val hosted = HashMap<Int, ActorRef<H>>()
    private val stopping = HashMap<ActorRef<*>, Int>()

    // Shards won, and who has yet to release each; and who has asked for a shard this node still holds.
    private val awaiting = HashMap<Int, MutableSet<Node>>()
    private val owed = HashMap<Int, MutableSet<Node>>()

    private val kept = ArrayDeque<Kept<M>>()
    private var asking = false

    // What a busy host could not take yet, per entity id and in order (spec 0095).
    @OptIn(PlumbingSeam::class)
    private val handOn = HandOn<String, M>()

    fun step(ctx: Ctx<Region<M>>, step: Region<M>) {
        handle(ctx, step)
        measure()
    }

    private fun measure() {
        meters.shards.set(ready.size.toDouble())
        meters.buffered.set(kept.size.toDouble())
    }

    private fun handle(ctx: Ctx<Region<M>>, step: Region<M>) = when (step) {
        // Another cluster reaches these entities only when this one accepts it (spec 0103).
        is Region.Envelope ->
            if (step.from == null || step.from in cluster.federatedFrom) route(ctx, step) else refuse(step)

        is Region.Viewed -> {
            moved = step.moved
            viewed(ctx, step.view)
        }

        is Region.Release -> if (outsider(step.sender)) refuse(step) else asked(step.shard, by = step.from)

        is Region.Released -> if (outsider(step.sender)) {
            refuse(step)
        } else {
            awaiting[step.shard]?.remove(step.by)
            startIfFree(ctx, step.shard)
        }

        is Region.Retry -> retry(ctx)

        is Region.Drain -> drain(ctx)

        is Region.AskAgain -> askAgain(ctx)

        // Taken before this step, in the region's behaviour.
        is Region.Look -> Unit
    }

    /** A shard's manager has stopped, and every entity of the shard with it. */
    fun ended(ctx: Ctx<Region<M>>, ref: ActorRef<*>) {
        // What stopped of its own accord, rather than let go, starts again on the shard's next message.
        hosted.entries.removeIf { it.value == ref }
        val shard = stopping.remove(ref) ?: return
        awaiting[shard]?.remove(self)
        startIfFree(ctx, shard)
        settle()
        measure()
    }

    /** Whether a handoff came in from a node that is not one of this cluster's members (spec 0103). */
    private fun outsider(sender: Node?) = sender != null && view.members.none { it.node == sender }

    /** A handoff from outside the cluster: a dead letter, and the shard stays where it is. */
    private fun refuse(step: Region<M>) =
        cluster.scope.deadLetter(DeadLetter(Address(self.toString(), path, 0), step, DeadLetter.Why.Unhandled))

    private fun owner(shard: Int) = hosting.owner(shard, view.members, moved)

    private fun mine(shard: Int) = owner(shard) == self

    private fun route(ctx: Ctx<Region<M>>, envelope: Region.Envelope<M>) {
        val shard = Placement.shardOf(envelope.id, shards)
        val owner = owner(shard)
        when {
            owner == self && shard in ready -> handOver(ctx, shard, envelope)

            owner == self || owner == null -> keep(envelope)

            envelope.hops >= Sharding.MOST_HOPS -> {
                // Passed between nodes whose views disagree: this one may already have the view that settles it.
                keep(envelope)
                ctx.timers.after(RetryKey, Sharding.RETRY_AFTER, Region.Retry())
            }

            else -> there(owner).tell(envelope.copy(hops = envelope.hops + 1))
        }
    }

    private fun viewed(ctx: Ctx<Region<M>>, next: View) {
        view = next
        ready.filterNot(::mine).forEach { letGo(ctx, it) }
        awaiting.keys.removeAll { !mine(it) }
        val holders = next.members.filter { it.status == Status.Up || it.status == Status.Leaving }
            .map { it.node }.filterTo(mutableSetOf()) { it != self }
        for (shard in 0 until shards) {
            if (mine(shard) && shard !in ready && shard !in awaiting) win(shard, holders)
        }
        awaiting.values.forEach { left -> left.retainAll(holders + self) }
        awaiting.keys.toList().forEach { startIfFree(ctx, it) }
        settle()
        retry(ctx)
        if (!asking && waitingOnOthers()) {
            asking = true
            ctx.timers.every(AskAgainKey, Sharding.ASK_AGAIN_EVERY, Region.AskAgain())
        }
    }

    private fun waitingOnOthers() = awaiting.values.any { left -> left.any { it != self } }

    /** Asks each member again for the shards it has yet to release, until none is left to ask. */
    private fun askAgain(ctx: Ctx<Region<M>>) {
        awaiting.forEach { (shard, left) ->
            left.filter { it != self }.forEach { there(it).tell(Region.Release(shard, self)) }
        }
        if (!waitingOnOthers()) {
            asking = false
            ctx.timers.cancel(AskAgainKey)
        }
    }

    private fun win(shard: Int, holders: Set<Node>) {
        awaiting[shard] = holders.toMutableSet().apply { if (shard in stopping.values) add(self) }
        holders.forEach { there(it).tell(Region.Release(shard, self)) }
    }

    private fun startIfFree(ctx: Ctx<Region<M>>, shard: Int) {
        if (awaiting[shard]?.isEmpty() != true || !mine(shard)) return
        awaiting -= shard
        ready += shard
        if (hosting.eager) host(ctx, shard)
        retry(ctx)
    }

    /** Tells the message to its target here, or keeps it while the target is too busy to take it (spec 0095). */
    @OptIn(PlumbingSeam::class)
    private fun handOver(ctx: Ctx<Region<M>>, shard: Int, envelope: Region.Envelope<M>) {
        val target = hosting.target(host(ctx, shard), envelope.id)
        if (handOn.tell(ctx, envelope.id, target, envelope.message)) {
            ctx.timers.after(DrainKey, Sharding.DRAIN_AFTER, Region.Drain())
        }
    }

    @OptIn(PlumbingSeam::class)
    private fun drain(ctx: Ctx<Region<M>>) {
        if (handOn.drain(ctx)) ctx.timers.after(DrainKey, Sharding.DRAIN_AFTER, Region.Drain())
    }

    /** The region is stopping: what it kept for busy hosts is lost with it. */
    @OptIn(PlumbingSeam::class)
    fun stopping(ctx: Ctx<Region<M>>) = handOn.drop(ctx, DeadLetter.Why.Stopped)

    private fun letGo(ctx: Ctx<Region<M>>, shard: Int) {
        // What was kept for the shard's entities goes wherever the shard is now, in order, with the next retry.
        @OptIn(PlumbingSeam::class)
        handOn.keys.filter { Placement.shardOf(it, shards) == shard }.forEach { id ->
            handOn.take(ctx, id) { keep(Region.Envelope(id, 0, it)) }
        }
        ready -= shard
        hosted.remove(shard)?.let { manager ->
            stopping[manager] = shard
            ctx.stop(manager)
        }
    }

    private fun asked(shard: Int, by: Node) {
        owed.getOrPut(shard) { mutableSetOf() } += by
        settle()
    }

    /** Answers everyone who asked for a shard this node neither owns, runs, nor is still stopping. */
    private fun settle() {
        owed.entries.removeIf { (shard, askers) ->
            val holding = mine(shard) || shard in ready || shard in stopping.values
            if (!holding) askers.forEach { there(it).tell(Region.Released(shard, self)) }
            !holding
        }
    }

    private fun host(ctx: Ctx<Region<M>>, shard: Int) =
        hosted.getOrPut(shard) { hosting.start(ctx, shard).also(ctx::watch) }

    private fun there(node: Node) = cluster.remote.remote(Address(node.toString(), path, 0), wire)

    /** Routes again what was kept, each with its hops back to none: the view it was kept under has changed. */
    private fun retry(ctx: Ctx<Region<M>>) {
        val waiting = kept.toList()
        kept.clear()
        waiting.forEach { Carriers.within(it.carried) { route(ctx, it.envelope.copy(hops = 0)) } }
    }

    private fun keep(envelope: Region.Envelope<M>) {
        if (kept.size < Sharding.KEEP_AT_MOST) return kept.addLast(Kept(envelope, Carriers.capture()))
        val recipient = Address(self.toString(), "$path/${envelope.id}", 0)
        cluster.scope.deadLetter(DeadLetter(recipient, envelope.message, DeadLetter.Why.Unreachable))
    }
}
