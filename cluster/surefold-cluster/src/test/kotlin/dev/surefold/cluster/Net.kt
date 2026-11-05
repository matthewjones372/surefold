package dev.surefold.cluster

import dev.surefold.actor.remote.Node
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

internal val settings = Gossiping(probeEvery = 1.seconds, ackWithin = 300.milliseconds, formAfter = 5.seconds)

internal fun at(n: Int) = Node("n$n", "10.0.0.$n", 25520)

/** Seeds as DNS gives them: host and port, no name. */
internal fun seeds(vararg n: Int) = n.map { Node("", at(it).host, at(it).port) }

/**
 * Nodes on a network that is a test step: every message sent in a step arrives within it, unless its link is cut or a
 * side has stopped. Time moves only when the test says.
 */
internal class Net(private val seed: Int = 1, private val downing: Downing = Downing.keepMajority(20.seconds)) {
    val nodes = mutableMapOf<Node, Membership>()
    private val cut = mutableSetOf<Set<Node>>()
    private val deaf = mutableSetOf<Set<Node>>()
    private val stopped = mutableSetOf<Node>()
    private val unheard = mutableSetOf<Node>()
    var now: Duration = Duration.ZERO
        private set

    fun start(n: Int, seeds: List<Node>, uid: Long = n.toLong(), ready: Boolean = true): Membership {
        val random = Random(seed * 31 + n + uid.toInt())
        return Membership(Incarnation(at(n), uid), { seeds }, settings, downing, random, now, ready).also {
            nodes[at(n)] = it
        }
    }

    fun stop(n: Int) {
        stopped += at(n)
    }

    fun stopped(n: Int) = at(n) in stopped

    /** Stops [n] once it is out of the cluster, after its last word, as a node's scope does when it closes. */
    fun goWhenOut(n: Int) {
        if (stopped(n) || !view(n).outFor(at(n))) return
        deliver(nodes.getValue(at(n)).farewell().mapTo(ArrayDeque()) { at(n) to it })
        stop(n)
    }

    fun cut(a: Int, b: Int) {
        cut += setOf(at(a), at(b))
    }

    /** Acks between [a] and [b] go missing while their gossip still arrives: a JVM too busy to ack in time. */
    fun dropAcks(a: Int, b: Int) {
        deaf += setOf(at(a), at(b))
    }

    /** The joins [n] sends go missing, as they do while the members it asks are too busy to welcome it. */
    fun loseJoinsFrom(n: Int) {
        unheard += at(n)
    }

    fun hearJoinsFrom(n: Int) {
        unheard -= at(n)
    }

    fun heal(a: Int, b: Int) {
        cut -= setOf(at(a), at(b))
    }

    fun isolate(n: Int, from: List<Int>) = from.forEach { cut(n, it) }

    fun rejoin(n: Int, from: List<Int>) = from.forEach { heal(n, it) }

    /** Runs up to [steps] steps of [every], stopping at the first after which [done] holds; whether it did. */
    fun until(steps: Int = 200, every: Duration = 100.milliseconds, done: () -> Boolean): Boolean {
        repeat(steps) {
            now += every
            val flying = ArrayDeque<Pair<Node, Send>>()
            live().forEach { (node, m) -> m.tick(now).forEach { flying += node to it } }
            deliver(flying)
            if (done()) return true
        }
        return false
    }

    private fun deliver(flying: ArrayDeque<Pair<Node, Send>>) {
        while (flying.isNotEmpty()) {
            val (from, send) = flying.removeFirst()
            val to = live().keys.firstOrNull { node -> reaches(send.to, node) && carried(from, node, send.message) }
            to?.let { there -> nodes.getValue(there).receive(send.message, now).forEach { flying += there to it } }
        }
    }

    private fun live() = nodes.filterKeys { it !in stopped }

    /** Whether the network carries [message] from [from] to [to]: not across a cut, nor lost as this net loses it. */
    private fun carried(from: Node, to: Node, message: Swim) =
        setOf(from, to) !in cut &&
            !(message is Swim.Ack && setOf(from, to) in deaf) &&
            !(message is Swim.Join && from in unheard)

    private fun reaches(address: Node, node: Node) =
        address.host == node.host && address.port == node.port && (address.name.isEmpty() || address.name == node.name)

    fun view(n: Int): View = nodes.getValue(at(n)).view()
}

internal fun View.up() = members.filter { it.status == Status.Up }.map { it.node }
