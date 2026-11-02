package io.github.matthewjones372.surefold.app.cluster

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import dev.surefold.actor.behaviour
import dev.surefold.actor.remote.node
import dev.surefold.actor.stay
import dev.surefold.logError
import dev.surefold.logInfo
import io.github.matthewjones372.surefold.app.Asked
import io.github.matthewjones372.surefold.app.Module
import io.github.matthewjones372.surefold.app.actor.Actors
import io.github.matthewjones372.surefold.app.actor.spawn
import io.github.matthewjones372.surefold.app.probe
import io.github.matthewjones372.surefold.app.single
import io.github.matthewjones372.surefold.app.typesafe.Reading
import io.github.matthewjones372.surefold.app.typesafe.config
import io.github.matthewjones372.surefold.cluster.Cluster
import io.github.matthewjones372.surefold.cluster.Gossiping
import io.github.matthewjones372.surefold.cluster.JoinOptions
import io.github.matthewjones372.surefold.cluster.Joining
import io.github.matthewjones372.surefold.cluster.Joins
import io.github.matthewjones372.surefold.cluster.MemberEvent
import io.github.matthewjones372.surefold.cluster.Ready
import io.github.matthewjones372.surefold.cluster.cluster
import kotlin.system.exitProcess
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.toKotlinDuration

/** What a node does once the others have downed it. */
enum class WhenDowned {
    /** Ends the process, so its orchestrator starts a new one that joins afresh: the only use of a downed node. */
    Exit,

    /** Stays up out of its cluster, for a test that looks at it afterwards. */
    Stay,
}

/** This node, how it joins, and how it behaves once in (spec 0096). */
class ClusterSettings(
    val name: String,
    val host: String,
    val port: Int,
    val joining: () -> Joining,
    val gossiping: Gossiping = Gossiping(),
    val leaveWithin: Duration = 30.seconds,
    val roles: Set<String> = emptySet(),
    val whenDowned: WhenDowned = WhenDowned.Exit,
)

/**
 * The [Cluster], joined as the HOCON section at [path] says: `node`, `join` and the section `join` names, then
 * `downing.stableAfter`, `gossip`, `leaveWithin`, `roles` and `whenDowned`, each with surefold's own default. The
 * backend `join` names is found on the classpath; one that is not there refuses the start, naming the module to add.
 */
fun cluster(path: String): Module = config(path) { clusterSettings(path) } + clustered { exitProcess(1) }

/** The [Cluster], joined as [settings] say, for an application that reads its settings its own way. */
fun cluster(settings: ClusterSettings): Module = single<ClusterSettings> { settings } + clustered { exitProcess(1) }

/**
 * Joins in the actors' scope, and is ready once this node is fully in. The joining closes after the cluster has
 * left: scope close hooks run last registered first, and the joining's is registered before the cluster's leave.
 * The node stays `Joining` until every node of the graph is built, so no shard is placed on it before its regions
 * are open (spec 0129), and only then is it asked whether it is in.
 */
internal fun clustered(exit: () -> Unit): Module =
    single { actors: Actors, settings: ClusterSettings ->
        val joining = try {
            settings.joining()
        } catch (refused: IllegalArgumentException) {
            refuse(refused.message ?: refused.toString())
        }
        val cluster = actors.within {
            onClose(joining::close)
            cluster(
                node(settings.name, settings.port, settings.host),
                joining.discovery,
                settings.gossiping,
                joining.downing,
                settings.leaveWithin,
                settings.roles,
                Ready.WhenTold,
            )
        }
        onStarted(cluster::admit)
        cluster.subscribe(spawn(actors, "surefold-cluster-log", logged(cluster)))
        if (settings.whenDowned == WhenDowned.Exit) {
            cluster.subscribe(spawn(actors, "surefold-cluster-downed", exitWhenDowned(cluster, exit)))
        }
        cluster
    }.probe(
        "cluster",
        timeout = 2.seconds,
        attempts = READY_ATTEMPTS,
        interval = 1.seconds,
        asked = Asked.WhenStarted,
    ) { cluster: Cluster ->
        cluster.ready()
    }

// Two minutes, a second apart: long enough for a node to join while another is being downed.
private const val READY_ATTEMPTS = 120

/** Every change to this node's view, logged: what an operator reads to know who left when, and which life. */
private fun logged(cluster: Cluster) = behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
    val member = event.member
    val life = if (cluster.isSelf(member)) "this node" else "${member.node}#${member.uid}"
    logInfo("cluster: ${event::class.simpleName} $life, ${member.status}; leader ${cluster.view.leader}")
    stay()
}

// On a thread of its own: the exit runs the shutdown hooks, which close the scope this actor is stepping in.
private fun exitWhenDowned(cluster: Cluster, exit: () -> Unit) = behaviour<MemberEvent, Unit>(Unit) { _, _, event ->
    // This life, not an earlier one at the same address: its downing reaches the life that replaced it (spec 0097).
    if (event is MemberEvent.Downed && cluster.isSelf(event.member)) {
        logError("${cluster.self} was downed by the others; ending the process so it can join again as a new node")
        Thread.ofPlatform().name("surefold-cluster-downed").start(exit)
    }
    stay()
}

internal fun Reading.clusterSettings(path: String): ClusterSettings {
    val name = string("node.name")
    val host = optional("node.host", "127.0.0.1") { getString(it) }
    val port = int("node.port")
    val join = string("join")
    val stableAfter = optional("downing.stableAfter", 20.seconds) { getDuration(it).toKotlinDuration() }
    val options = if (join.isEmpty()) {
        emptyMap()
    } else {
        optional(join, emptyMap<String, Any?>()) { getConfig(it).root().unwrapped() }
    }
    val defaults = Gossiping()
    val gossiping = Gossiping(
        probeEvery = optional("gossip.probeEvery", defaults.probeEvery) { getDuration(it).toKotlinDuration() },
        ackWithin = optional("gossip.ackWithin", defaults.ackWithin) { getDuration(it).toKotlinDuration() },
        formAfter = optional("gossip.formAfter", defaults.formAfter) { getDuration(it).toKotlinDuration() },
    )
    return ClusterSettings(
        name = name,
        host = host,
        port = port,
        joining = { Joins.named(join, JoinOptions(port, stableAfter, options, "$path.$join")) },
        gossiping = gossiping,
        leaveWithin = optional("leaveWithin", 30.seconds) { getDuration(it).toKotlinDuration() },
        roles = optional("roles", emptyList<String>()) { getStringList(it) }.toSet(),
        whenDowned = optional("whenDowned", WhenDowned.Exit) { key ->
            val said = getString(key)
            WhenDowned.entries.firstOrNull { it.name.equals(said, ignoreCase = true) }
                ?: throw ConfigException.BadValue(key, "is exit or stay, not $said")
        },
    )
}

private fun <A> Reading.optional(key: String, default: A, read: Config.(String) -> A): A =
    of(default) { if (hasPath(key)) read(key) else default }
