package io.github.matthewjones372.surefold.actor.benchmarks

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.Scope
import dev.surefold.actor.Behaviour
import dev.surefold.actor.become
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.Node
import dev.surefold.actor.remote.node
import dev.surefold.actor.spawn
import dev.surefold.actor.stay
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.Discovery
import dev.surefold.cluster.Gossiping
import dev.surefold.cluster.MemberEvent
import dev.surefold.cluster.cluster
import dev.surefold.scope
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.Behaviors
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import org.apache.pekko.cluster.Cluster as PekkoCluster

/** Three nodes on each side, as spec 0092 asks: enough that an entity's owner is usually another node. */
internal const val NODES = 3

/** Far past the time three nodes on loopback take to agree they are up. */
private const val FORM_WITHIN_SECONDS = 60L

/** Node names both sides use, so an entity answering where it runs answers the same on each. */
internal fun nodeName(index: Int) = "n${index + 1}"

/** The default probes, which a node under load needs; only forming sooner, since a trial starts with it. */
private val gossiping = Gossiping(formAfter = 1.seconds)

/**
 * Three surefold nodes of one cluster in this JVM on loopback, each a scope held open on a thread of its own, with what
 * [build] started on each. It returns once every node sees all three `Up`.
 */
internal class SurefoldTrio<T>(build: Scope<Nothing>.(cluster: Cluster, name: String) -> T) {
    val parts: List<T>
    private val release = CountDownLatch(1)
    private val holders: List<Thread>

    init {
        val ports = List(NODES) { freePort() }
        val seeds = ports.map { Node("", "127.0.0.1", it) }.let { nodes -> Discovery { nodes } }
        val up = CountDownLatch(NODES)
        val built = CountDownLatch(NODES)
        val opened = List(NODES) { AtomicReference<T>() }
        holders = ports.mapIndexed { i, port ->
            Thread.ofPlatform().start {
                scope {
                    val name = nodeName(i)
                    // No leaving on close: a trial's nodes all stop together, and none is left to hand shards to.
                    val cluster = cluster(node(name, port), seeds, gossiping, leaveWithin = Duration.ZERO)
                    cluster.subscribe(spawn("all-up", seesAllUp(up)))
                    opened[i].set(build(cluster, name))
                    built.countDown()
                    release.await()
                }
            }
        }
        built.await()
        check(up.await(FORM_WITHIN_SECONDS, TimeUnit.SECONDS)) { "three surefold nodes did not all come up" }
        parts = opened.map { it.get() }
    }

    fun close() {
        release.countDown()
        holders.forEach(Thread::join)
    }
}

/** Counts [up] down once, when this node has seen every member `Up`. */
private fun seesAllUp(up: CountDownLatch): Behaviour<MemberEvent, Set<Node>, Nothing> =
    behaviour(emptySet()) { _, seen, event ->
        if (event is MemberEvent.Up) {
            val now = seen + event.member.node
            if (now.size == NODES && seen.size < NODES) up.countDown()
            become(now)
        } else {
            stay()
        }
    }

/**
 * Three Pekko Typed systems of one cluster in this JVM on loopback, with Artery over TCP and the benchmarks'
 * serializer. It returns once every node is `Up`.
 */
internal class PekkoTrio(extra: String = "") {
    val systems: List<ActorSystem<Any>>

    init {
        val ports = List(NODES) { freePort() }
        val seeds = ports.joinToString { "\"pekko://cluster@127.0.0.1:$it\"" }
        systems = ports.map { port ->
            ActorSystem.create(Behaviors.empty<Any>(), "cluster", config(port, seeds, extra))
        }
        val up = CountDownLatch(NODES)
        systems.forEach { PekkoCluster.get(Adapter.toClassic(it)).registerOnMemberUp { up.countDown() } }
        check(up.await(FORM_WITHIN_SECONDS, TimeUnit.SECONDS)) { "three Pekko nodes did not all come up" }
    }

    fun close() {
        systems.forEach { it.terminate() }
        systems.forEach { it.whenTerminated.toCompletableFuture().join() }
    }

    private fun config(port: Int, seeds: String, extra: String): Config = ConfigFactory.parseString(
        """
        pekko.loglevel = WARNING
        pekko.actor.provider = cluster
        pekko.actor.allow-java-serialization = off
        pekko.actor.serializers.surefold-benchmarks = "${WireSerializer::class.java.name}"
        pekko.actor.serialization-bindings { "${PekkoWire::class.java.name}" = surefold-benchmarks }
        pekko.remote.artery.transport = tcp
        pekko.remote.artery.canonical.hostname = "127.0.0.1"
        pekko.remote.artery.canonical.port = $port
        pekko.cluster.seed-nodes = [$seeds]
        pekko.cluster.jmx.multi-mbeans-in-same-jvm = on
        $extra
        """.trimIndent(),
    ).withFallback(ConfigFactory.load())
}
