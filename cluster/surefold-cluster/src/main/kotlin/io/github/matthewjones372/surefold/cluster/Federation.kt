package io.github.matthewjones372.surefold.cluster

import dev.surefold.increment
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Address
import io.github.matthewjones372.surefold.actor.DeadLetter
import io.github.matthewjones372.surefold.actor.counter
import io.github.matthewjones372.surefold.actor.deadLetter
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Another cluster, [name], whose sharded entities this one reaches without ever joining it (spec 0103): its nodes are
 * found by [nodes], as seeds are, and an envelope goes to its kind's region on one of them, which routes it on to the
 * owner as it does its own members'. The two never gossip, down or watch each other. Each envelope says it is [from]
 * this cluster, and the other takes it only once it has `acceptFederated` that name.
 *
 * Nodes are tried in [nodes]' order. One a frame could not be sent to is skipped for [retryAfter], or until it
 * connects again; with every one failing, the first is tried anyway, so a tell is a dead letter and an ask fails at
 * once with `Unreachable`, and either goes through again as soon as a node is back.
 */
class Federated internal constructor(
    val name: String,
    private val cluster: Cluster,
    private val nodes: Discovery,
    private val from: String,
    private val retryAfter: Duration,
) {
    private val sent = cluster.scope.counter("surefold.federation.sent", "cluster" to name)
    private val unreachable = cluster.scope.counter("surefold.federation.unreachable", "cluster" to name)

    /** [kind]'s entities in this cluster, whose messages [codec] writes as the other cluster's own kind reads them. */
    fun <M : Any> sharding(kind: String, codec: MessageCodec<M>): FederatedKind<M> =
        FederatedKind(kind, this, RegionCodec(codec))

    /** [envelope] sent to [path]'s region on the first node that is not failing, or a dead letter with none known. */
    internal fun <M : Any> send(path: String, wire: RegionCodec<M>, envelope: Region.Envelope<M>) {
        val known = nodes.seeds()
        val node = known.firstOrNull { !cluster.remote.failing(it, retryAfter) }
            ?: known.firstOrNull().also { unreachable.increment() }
        if (node == null) {
            val nowhere = Address("$name@", "$path/${envelope.id}", 0)
            return cluster.scope.deadLetter(DeadLetter(nowhere, envelope.message, DeadLetter.Why.Unreachable))
        }
        sent.increment()
        cluster.remote.remote(Address(node.toString(), path, 0), wire).tell(envelope.copy(from = from))
    }
}

/** One kind of another cluster's entities, reached through [Federated]. */
class FederatedKind<M : Any> internal constructor(
    val kind: String,
    private val federated: Federated,
    private val wire: RegionCodec<M>,
) {
    private val path = Sharding.path(kind)

    /** The entity [id] in the other cluster, wherever it runs there. */
    fun entity(id: String): ActorRef<M> = FederatedRef(federated, path, wire, id)
}

private class FederatedRef<M : Any>(
    private val federated: Federated,
    private val path: String,
    private val wire: RegionCodec<M>,
    private val id: String,
) : ActorRef<M> {
    override val address = Address("${federated.name}@", "$path/$id", 0)

    override fun tell(message: M) = federated.send(path, wire, Region.Envelope(id, 0, message))

    override fun equals(other: Any?) = other is FederatedRef<*> && other.address == address

    override fun hashCode() = address.hashCode()

    override fun toString() = "FederatedRef(${federated.name}$path/$id)"
}

/**
 * Another cluster, [name], found by [nodes], whose sharded entities this one reaches without joining it (spec 0103),
 * saying it is [from], the name the other cluster accepts. A node a frame could not be sent to is skipped for
 * [retryAfter]. Federation runs over TLS, whose certificates are what tie a peer to the name it gives: this node needs
 * a `Tls`, unless [plaintext] says otherwise, as only a test should.
 */
fun Cluster.federate(
    name: String,
    nodes: Discovery,
    from: String,
    retryAfter: Duration = 5.seconds,
    plaintext: Boolean = false,
): Federated {
    require(from.isNotBlank()) { "a cluster federating says which it is, and from was blank" }
    require(remote.secured || plaintext) {
        "federating with $name needs TLS: give this node a Tls, or pass plaintext = true in a test"
    }
    return Federated(name, this, nodes, from, retryAfter)
}
