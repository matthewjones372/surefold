package io.github.matthewjones372.surefold.cluster

import dev.surefold.Scope
import dev.surefold.actor.ActorRef
import dev.surefold.actor.Address
import dev.surefold.actor.Ctx
import dev.surefold.actor.HandOn
import dev.surefold.actor.Next
import dev.surefold.actor.PlumbingSeam
import dev.surefold.actor.Signal
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.counter
import dev.surefold.actor.gauge
import dev.surefold.actor.journal
import dev.surefold.actor.onSignal
import dev.surefold.actor.onStart
import dev.surefold.actor.remote.Lane
import dev.surefold.actor.remote.MessageCodec
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.RemoteNode
import dev.surefold.actor.remote.WireIn
import dev.surefold.actor.remote.WireOut
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.actor.stop
import dev.surefold.clock
import dev.surefold.logWarn
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

private const val CLUSTER = "cluster"

/** What the cluster actor handles: a message from another node's, a tick of its own, or a request from this node. */
internal sealed interface Step {
    data class Heard(val message: Swim) : Step

    data object Tick : Step

    data object Leave : Step

    data class Subscribe(val to: ActorRef<MemberEvent>) : Step

    data class Move(val kind: String, val to: Map<Int, Node?>) : Step

    data object Admit : Step
}

private val StepCodec = object : MessageCodec<Step> {
    override fun write(message: Step, out: WireOut) = when (message) {
        is Step.Heard -> SwimCodec.write(message.message, out)
        Step.Tick, Step.Leave, is Step.Subscribe, is Step.Move, Step.Admit -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Step = Step.Heard(SwimCodec.read(input))
}

private typealias Subscribers = Set<ActorRef<MemberEvent>>

/** When a node that joins may be moved on to `Up`, and so be given shards, workers and singletons (spec 0129). */
enum class Ready {
    /** As soon as every member has heard it join. */
    AtOnce,

    /** Only once [Cluster.admit] says it is ready: it stays `Joining` until then. */
    WhenTold,
}

/**
 * [node] as a member of a cluster whose seeds [discovery] finds, until the scope closes, when it leaves first:
 * waiting up to [leaveWithin] to be out, before the scope stops its actors (spec 0080). A [leaveWithin] of zero
 * leaves nothing: the node goes as a crashed one does, and the others down it. [roles] say what this node was
 * started to do, and every member sees them in its view (spec 0083). With [ready] `WhenTold` it stays `Joining` until
 * [Cluster.admit], so nothing is placed on it before what it hosts is open (spec 0129). The membership runs in an
 * actor of its own, which the nodes of the cluster reach at the same path on each, and from now on it decides when
 * a watch on another node's actor ends. A partition is resolved by [downing]; a node downed stops that actor, and its
 * subscribers hear it downed and every other member removed: whether the process ends is theirs to decide.
 */
fun <F> Scope<F>.cluster(
    node: RemoteNode,
    discovery: Discovery,
    gossiping: Gossiping = Gossiping(),
    downing: Downing = Downing.keepMajority(),
    leaveWithin: Duration = 30.seconds,
    roles: Set<String> = emptySet(),
    ready: Ready = Ready.AtOnce,
): Cluster {
    require(roles.none(String::isBlank)) { "a role needs a name, was $roles" }
    val time = clock.get()
    val now = { time.now().let { it.epochSecond.seconds + it.nano.nanoseconds } }
    val uid = Random.nextLong()
    val membership = Membership(
        Incarnation(node.self, uid, roles),
        discovery::seeds,
        gossiping,
        downing,
        Random.Default,
        now(),
        ready = ready == Ready.AtOnce,
    )
    val cluster = Cluster(node.self, node, this, leaveWithin, uid)
    val steps = Steps(node, membership, cluster, node.takeOverWatches(), now)
    val ref = spawn(
        CLUSTER,
        behaviour<Step, Subscribers>(emptySet()) { ctx, subscribers, step -> steps.step(ctx, subscribers, step) }
            .onStart { ctx -> ctx.timers.every(Step.Tick, gossiping.ackWithin / 2, Step.Tick) }
            .onSignal { ctx, subscribers, signal ->
                when (signal) {
                    is Signal.Terminated -> {
                        steps.gone(ctx, signal.ref)
                        become(subscribers.filterTo(mutableSetOf()) { it != signal.ref })
                    }

                    Signal.Stopping -> stay()
                }
            },
        // Run ahead of the node's entities (spec 0104), so a probe is not acked late because thousands of them wait.
        urgent = true,
    )
    // Membership on the control lane (spec 0104): a probe or its ack never waits behind, or is dropped with, the data.
    node.expose(ref, StepCodec, Lane.Control)
    cluster.actor = ref
    if (leaveWithin.isPositive()) {
        onClose {
            val left = cluster.stop(leaveWithin)
            if (!left) logWarn("${node.self} did not leave its cluster within $leaveWithin; closing anyway")
        }
    }
    return cluster
}

/** What the cluster actor does with each step: the membership's part, then what changed told to whoever hears it. */
private class Steps(
    private val node: RemoteNode,
    private val membership: Membership,
    private val cluster: Cluster,
    private val endWatches: (Node) -> Unit,
    private val now: () -> Duration,
) {
    // What a subscriber too busy to take it is owed, in order: the cluster actor never waits on a subscriber, and a
    // tell from its step to a full mailbox would throw and stop it (spec 0101).
    @OptIn(PlumbingSeam::class)
    private val owed = HandOn<ActorRef<MemberEvent>, MemberEvent>()

    fun step(ctx: Ctx<Step>, subscribers: Subscribers, step: Step): Next<Subscribers> {
        // Downed, the actor only hands on what its subscribers are owed, the downing among it, and then stops.
        if (membership.downed) return handedOn(ctx)
        when (step) {
            is Step.Heard -> send(membership.receive(step.message, now()))

            Step.Tick -> {
                hand(ctx)
                report()
                rebalance()
                send(membership.tick(now()))
            }

            Step.Leave -> membership.leave()

            Step.Admit -> membership.markReady()

            is Step.Move -> membership.move(step.kind, step.to)

            is Step.Subscribe -> {
                ctx.watch(step.to)
                changes(View.None, cluster.view).forEach { tell(ctx, step.to, it) }
                return become(subscribers + step.to)
            }
        }
        publish(ctx, subscribers)
        return if (membership.downed) handedOn(ctx) else stay()
    }

    @OptIn(PlumbingSeam::class)
    private fun tell(ctx: Ctx<Step>, subscriber: ActorRef<MemberEvent>, event: MemberEvent) {
        owed.tell(ctx, subscriber, subscriber, event)
    }

    @OptIn(PlumbingSeam::class)
    private fun hand(ctx: Ctx<Step>) = owed.drain(ctx)

    private fun handedOn(ctx: Ctx<Step>): Next<Subscribers> = if (hand(ctx)) stay() else stop()

    /** A subscriber that stopped is owed nothing more. */
    @OptIn(PlumbingSeam::class)
    fun gone(ctx: Ctx<Step>, subscriber: ActorRef<*>) {
        @Suppress("UNCHECKED_CAST")
        owed.forget(ctx, subscriber as ActorRef<MemberEvent>)
    }

    private fun report() = cluster.meters.forEach { meter ->
        meter.due(now())?.let { membership.report(meter.kind, it) }
    }

    private val balancer = Balancer()

    private fun rebalance() = balancer.moves(now(), cluster.meters, cluster.view, node.self, cluster.balance)
        .forEach { (kind, moves) -> if (moves.isNotEmpty()) membership.move(kind, moves) }

    private fun send(sends: List<Send>) = sends.forEach { (to, message) ->
        node.remote(Address(to.toString(), "/user/$CLUSTER", 0), StepCodec).tell(Step.Heard(message))
    }

    // Held once, and set on each step that publishes, by the one actor that owns the view (spec 0081).
    private val members =
        Status.entries.associateWith { status ->
            cluster.scope.gauge("surefold.cluster.members", "status" to status.name)
        }
    private val unreachable = cluster.scope.gauge("surefold.cluster.unreachable")
    private val leader = cluster.scope.gauge("surefold.cluster.leader")
    private val downed = cluster.scope.counter("surefold.cluster.downed")

    private fun measure(view: View, events: List<MemberEvent>) {
        val counts = view.members.groupingBy { it.status }.eachCount()
        members.forEach { (status, gauge) -> gauge.set((counts[status] ?: 0).toDouble()) }
        unreachable.set(view.unreachable.size.toDouble())
        leader.set(if (view.leader == node.self) 1.0 else 0.0)
        downed.increment(events.count { it is MemberEvent.Downed }.toDouble())
    }

    /**
     * Watches end with the life they were made on (spec 0097). A later life seen at an address ends the watches held
     * there, since nothing could have watched its actors before it was known; removing an earlier life then ends
     * none, since by that time they may be on the later life's actors.
     */
    private fun endWatches(before: View, next: View, events: List<MemberEvent>) {
        fun Member.lifeIn(view: View) = view.members.any { it.node == node && it.uid == uid }
        fun Member.laterIn(view: View) = view.members.any { it.node == node && it.uid != uid && it.status.isLive }
        val replaced = next.members.filter { member ->
            member.node != node.self && !member.lifeIn(before) && before.members.any { it.node == member.node }
        }
        val removed = events.filterIsInstance<MemberEvent.Removed>().map { it.member }.filterNot { it.laterIn(next) }
        (replaced + removed).map { it.node }.distinct().forEach(endWatches)
    }

    private fun publish(ctx: Ctx<Step>, subscribers: Subscribers) {
        val next = membership.view()
        // Before the view that lets stop return is published, so the scope's close does not stop the node first.
        if (next.outFor(node.self, cluster.uid)) send(membership.farewell())
        // A downed node is out of the cluster: every other member is gone as far as it is concerned.
        val gone = if (membership.downed) next.members.filter { it.node != node.self } else emptyList()
        val events = changes(cluster.view, next) + gone.map(MemberEvent::Removed)
        endWatches(cluster.view, next, events)
        subscribers.forEach { subscriber -> events.forEach { tell(ctx, subscriber, it) } }
        measure(next.measuredBy(node.self, membership.downed), events)
        cluster.publish(next, membership.balance())
    }
}

/**
 * One node's membership of a cluster: the view it has now, and a way to leave. A node that restarts at the same
 * address is a new life of it, with a new [uid]: [isSelf] tells this life from an earlier one (spec 0097).
 */
class Cluster internal constructor(
    val self: Node,
    internal val remote: RemoteNode,
    internal val scope: Scope<*>,
    internal val leaveWithin: Duration = Duration.ZERO,
    /** This life's, as its [Member] carries it: a restart at [self]'s address is a member with another. */
    val uid: Long = 0,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val viewers = CopyOnWriteArrayList<(View) -> Unit>()

    /** What each kind that rebalances counts here, for the cluster actor to report (spec 0090). */
    internal val meters = CopyOnWriteArrayList<LoadMeter>()

    // The other clusters whose federated envelopes this one's regions take (spec 0103): none until said.
    internal val federatedFrom: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Lets the clusters named [clusters], federating with this one as `federate(…, from = name)`, reach its sharded
     * entities. An envelope from any other cluster is a dead letter at the region it reaches (spec 0103).
     */
    fun acceptFederated(vararg clusters: String) {
        require(clusters.none(String::isBlank)) { "a cluster to accept needs a name" }
        federatedFrom += clusters
    }

    @Volatile
    var view: View = View.None
        private set

    internal lateinit var actor: ActorRef<Step>

    /** The registry of durable producers, from this node's first sharding on (spec 0099); none without a journal. */
    internal val producers: Producers? by lazy { if (keepsJournal()) Producers(this) else null }

    // The scope's journal is set before its cluster's first sharding, or its durable producers could not start.
    private fun keepsJournal(): Boolean = try {
        scope.journal()
        true
    } catch (_: IllegalStateException) {
        false
    }

    /**
     * Whether [member] is this life of this node, not an earlier one at the same address: an earlier life's `Downed`
     * and `Removed` reach the life that replaced it, and are not about it.
     */
    fun isSelf(member: Member): Boolean = member.node == self && member.uid == uid

    /** This life: what a durable producer's id carries, so each life of a node keeps its own (spec 0099). */
    internal val life: Life get() = Life(self, uid)

    /** The load of the kinds that rebalance, as of [view]: published with it, and waited on as it is. */
    @Volatile
    internal var balance: Balance = Balance.None
        private set

    /** Says this node is ready to be `Up`, when it joined with `Ready.WhenTold`; with `AtOnce` it changes nothing. */
    fun admit() = actor.tell(Step.Admit)

    /** Asks to leave: the oldest member removes this one once every member has seen it go. */
    fun leave() = actor.tell(Step.Leave)

    /**
     * Whether this node is fully in its cluster, for a readiness probe (spec 0081): `Up`, with a leader, and every
     * member it sees reachable. While one is unreachable the cluster moves no one on, so the shards it owned answer
     * nothing; a node that is `Joining`, `Leaving`, downed or waiting for a partition to be decided is not ready.
     */
    fun ready(): Boolean {
        val now = view
        return now.members.any { isSelf(it) && it.status == Status.Up } &&
            now.leader != null &&
            now.unreachable.isEmpty()
    }

    /**
     * Leaves, and waits up to [within] until this node is out of the cluster (spec 0080): removed by the others, or
     * downed, or with no other member `Up`, so nobody to hand its shards to or to remove it. While it is `Leaving` its
     * shards move to their next owners. Whether it was out in time. The scope's close does this by itself, before its
     * actors stop.
     */
    fun stop(within: Duration): Boolean {
        leave()
        return await(within) { view -> view.outFor(self, uid) }
    }

    /**
     * Tells [subscriber] every [MemberEvent] from now on, until it stops: first the view as it is, as each member Up
     * and each one unreachable, then each change.
     */
    fun subscribe(subscriber: ActorRef<MemberEvent>) = actor.tell(Step.Subscribe(subscriber))

    internal fun publish(next: View, loads: Balance = balance) {
        if (next == view && loads == balance) return
        val placed = next != view || loads.moved != balance.moved
        balance = loads
        // The viewers first: a region is told the view before anyone waiting on it wakes, so what a waiter tells a
        // region after its wait is routed by that view or a later one, never an earlier.
        if (placed) viewers.forEach { it(next) }
        lock.withLock {
            view = next
            changed.signalAll()
        }
    }

    /** Moves [shard] of [kind] to [to], or back to its hash owner if null, if this node leads (spec 0090). */
    internal fun move(kind: String, shard: Int, to: Node?) = actor.tell(Step.Move(kind, mapOf(shard to to)))

    /** Calls [viewer] with the view now, and with each new one after, on the cluster actor's step. */
    internal fun onView(viewer: (View) -> Unit) {
        viewers += viewer
        viewer(view)
    }

    /** Waits up to [within] for a view that [until] holds for; whether one came. */
    internal fun await(within: Duration, until: (View) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(view)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

/**
 * Whether [self] is out of the cluster this view shows: no longer a live member, or with no other member `Up`. With
 * none `Up` there is no leader to remove it, and a member `Down` or `Joining` would keep it waiting for nothing.
 */
internal fun View.outFor(self: Node, uid: Long? = null): Boolean =
    members.none { it.node == self && (uid == null || it.uid == uid) && it.status.isLive } ||
        members.none { it.node != self && it.status == Status.Up }

/**
 * This view as [self] measures it: all of it, or, once [self] has downed itself, [self] alone, since every other
 * member is gone as far as it is concerned, and it has no leader and nothing it could reach.
 */
internal fun View.measuredBy(self: Node, downed: Boolean): View =
    if (downed) View(members.filter { it.node == self }, emptySet(), null) else this
