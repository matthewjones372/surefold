package io.github.matthewjones372.surefold.cluster

import dev.surefold.logWarn
import io.github.matthewjones372.surefold.actor.ActorRef
import io.github.matthewjones372.surefold.actor.Ctx
import io.github.matthewjones372.surefold.actor.Delivered
import io.github.matthewjones372.surefold.actor.Delivery
import io.github.matthewjones372.surefold.actor.EventCodec
import io.github.matthewjones372.surefold.actor.JournalPruning
import io.github.matthewjones372.surefold.actor.PersistenceId
import io.github.matthewjones372.surefold.actor.Producer
import io.github.matthewjones372.surefold.actor.delivered
import io.github.matthewjones372.surefold.actor.onStart
import io.github.matthewjones372.surefold.actor.persistent
import io.github.matthewjones372.surefold.actor.producer
import io.github.matthewjones372.surefold.actor.remote.MessageCodec
import io.github.matthewjones372.surefold.actor.remote.Node
import io.github.matthewjones372.surefold.actor.remote.WireIn
import io.github.matthewjones372.surefold.actor.remote.WireOut
import io.github.matthewjones372.surefold.actor.remote.delivery
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/** One life of a node, as a durable producer's id carries it: its name, and its uid in base 36. */
internal data class Life(val node: Node, val uid: Long) {
    override fun toString() = "${node.name}-${uid.toULong().toString(radix = 36)}"
}

/** A durable producer as the registry lists it: the kind it sends to, the life that made it, and the one running it. */
internal data class Listed(val kind: String, val owner: Life, val runner: Life)

/** What the `surefold-producers` singleton handles. */
internal sealed interface Registry {
    /** [owner] runs the durable producer [producer], which sends to [kind]'s entities. */
    data class Register(
        val producer: String,
        val kind: String,
        val owner: Life,
        override val delivery: Delivery,
    ) : Registry,
        Delivered

    /** The singleton's own timer: resume what no live life runs, and retire what has drained. */
    data object Check : Registry
}

private typealias Registered = Map<String, Listed>

/** What the registry writes: a producer listed, or listed again with another runner, and one retired. */
private sealed interface Change {
    data class Enlisted(val producer: String, val listed: Listed) : Change

    data class Retired(val producer: String) : Change
}

private const val ENLISTED = 1
private const val RETIRED = 2

/** How often the registry looks for producers to resume, and for resumed ones that have drained. */
private val CHECK_EVERY = 500.milliseconds

/** The key of the check made at once on each start, beside the one every [CHECK_EVERY]. */
private object FirstCheck

private const val REGISTRY = "surefold-producers"

/**
 * This node's part in the registry of durable producers (spec 0099): the singleton itself, which runs on one node of
 * the cluster, and this node's registrations, sent to it at least once. [listed] is what the singleton lists, on the
 * node it runs on, and null on a node it has not run on.
 */
internal class Producers(private val cluster: Cluster) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()

    @Volatile
    var listed: Registered? = null
        private set

    private val kinds = ConcurrentHashMap<String, Sharded<*>>()

    private val registry = cluster.singleton(REGISTRY, RegistryCodec) { registry(Resuming(cluster, kinds), ::publish) }

    private val registrations by lazy {
        cluster.scope.producer("$REGISTRY-${cluster.life}", drainWithin = cluster.leaveWithin) {
            registry
        }
    }

    /** Lets the registry resume [sharded]'s producers on this node. */
    fun kind(sharded: Sharded<*>) {
        kinds[sharded.kind] = sharded
    }

    /** Lists [producer], of [kind]'s entities, as this life's. */
    fun register(producer: String, kind: String, within: Duration) {
        registrations.send(REGISTRY) { Registry.Register(producer, kind, cluster.life, it) }
        // Waited for, so that a node which crashes once it has sent leaves its commands listed for resuming.
        if (!registrations.drain(within)) logWarn("$producer is not registered yet; it is sent again until it is")
    }

    private fun publish(now: Registered) = lock.withLock {
        listed = now
        changed.signalAll()
    }

    /** Waits up to [within] for [listed] to be one that [until] holds for; whether it came. */
    fun await(within: Duration, until: (Registered?) -> Boolean): Boolean = lock.withLock {
        var left = within.inWholeNanoseconds
        while (!until(listed)) {
            if (left <= 0) return false
            left = changed.awaitNanos(left)
        }
        true
    }
}

/**
 * The registry: every durable producer and the life that runs it, told to [listed] as it changes. A producer whose
 * runner has left the view, or that an earlier run of the singleton resumed, is resumed here, as a child, so it stops
 * with the singleton and the next one resumes it again.
 */
private fun registry(resuming: Resuming, listed: (Registered) -> Unit) = delivered(
    persistent<Registry, Change, Registered>(
        id = PersistenceId(REGISTRY, REGISTRY),
        empty = emptyMap(),
        codec = ChangeCodec,
        command = { ctx, registered, message ->
            when (message) {
                is Registry.Register -> {
                    val listing = Listed(message.kind, message.owner, message.owner)
                    if (message.producer in registered) {
                        none()
                    } else {
                        persist(Change.Enlisted(message.producer, listing)).then(listed)
                    }
                }

                // One change a step, and the next check at once, while there is more to resume or retire.
                Registry.Check -> resuming.next(ctx, registered)?.let { change ->
                    persist(change).then { after ->
                        listed(after)
                        ctx.self.tell(Registry.Check)
                    }
                } ?: none().then(listed)
            }
        },
        event = { registered, change ->
            when (change) {
                is Change.Enlisted -> registered + (change.producer to change.listed)
                is Change.Retired -> registered - change.producer
            }
        },
    ),
).onStart { ctx ->
    resuming.started()
    ctx.timers.every(Registry.Check, CHECK_EVERY, Registry.Check)
    ctx.timers.after(FirstCheck, Duration.ZERO, Registry.Check)
}

/** The producers the registry has resumed on this node, each with the child that runs it. */
private class Resuming(private val cluster: Cluster, private val kinds: Map<String, Sharded<*>>) {
    private val running = HashMap<String, Pair<Producer<*>, ActorRef<*>>>()

    /** A start, or a restart, has no children yet. */
    fun started() = running.clear()

    /** Resumes a producer that needs a runner, or else retires a resumed one that has drained, and says which. */
    fun next(ctx: Ctx<Registry>, registered: Registered): Change? {
        val live = cluster.view.members.mapTo(HashSet()) { Life(it.node, it.uid) }
        val orphan = registered.entries.firstOrNull { (producer, listed) ->
            val orphaned = listed.runner !in live || listed.runner != listed.owner
            producer !in running && orphaned && listed.kind in kinds
        }
        if (orphan != null) {
            val (producer, listed) = orphan
            running[producer] = kinds.getValue(listed.kind).resume(ctx, producer)
            return Change.Enlisted(producer, listed.copy(runner = cluster.life))
        }
        val drained = running.entries.firstOrNull { it.value.first.drain(Duration.ZERO) } ?: return null
        ctx.stop(drained.value.second)
        running -= drained.key
        (ctx.journal as? JournalPruning)?.deleteTo(PersistenceId("surefold-producer", drained.key), Long.MAX_VALUE)
        return Change.Retired(drained.key)
    }
}

private fun WireOut.life(life: Life) {
    string(life.node.toString())
    long(life.uid)
}

private fun WireIn.life() = Life(Node.parse(string()), long())

private object RegistryCodec : MessageCodec<Registry> {
    override fun write(message: Registry, out: WireOut) = when (message) {
        is Registry.Register -> {
            out.string(message.producer)
            out.string(message.kind)
            out.life(message.owner)
            out.delivery(message.delivery)
        }

        Registry.Check -> error("$message never leaves its node")
    }

    override fun read(input: WireIn): Registry =
        Registry.Register(input.string(), input.string(), input.life(), input.delivery())
}

private object ChangeCodec : EventCodec<Change> {
    override fun encode(event: Change): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { data ->
            when (event) {
                is Change.Enlisted -> {
                    data.writeInt(ENLISTED)
                    data.writeUTF(event.producer)
                    data.writeUTF(event.listed.kind)
                    listOf(event.listed.owner, event.listed.runner).forEach { life ->
                        data.writeUTF(life.node.toString())
                        data.writeLong(life.uid)
                    }
                }

                is Change.Retired -> {
                    data.writeInt(RETIRED)
                    data.writeUTF(event.producer)
                }
            }
        }
    }.toByteArray()

    override fun decode(bytes: ByteArray): Change = DataInputStream(ByteArrayInputStream(bytes)).use { data ->
        fun life() = Life(Node.parse(data.readUTF()), data.readLong())
        when (val tag = data.readInt()) {
            ENLISTED -> Change.Enlisted(data.readUTF(), Listed(data.readUTF(), life(), life()))
            RETIRED -> Change.Retired(data.readUTF())
            else -> error("no registry event has the tag $tag")
        }
    }
}
