package dev.surefold.cluster

import dev.surefold.actor.remote.Node
import java.util.ServiceLoader
import kotlin.time.Duration

/**
 * How a node joins its cluster: where it finds seeds, how an even split is broken, and whatever client the two share,
 * which [close] releases. A backend builds all three together, so an application never assembles them (spec 0096).
 */
class Joining(val discovery: Discovery, val downing: Downing, private val release: () -> Unit = {}) : AutoCloseable {
    override fun close() = release()
}

/**
 * A way to join that config names: `join = kubernetes` finds the [Joins] whose [name] is `kubernetes` on the
 * classpath, so the module choosing it need not depend on every backend.
 */
interface Joins {
    val name: String

    fun joining(options: JoinOptions): Joining

    companion object {
        // Each backend's module, so a name nobody registered says what to add.
        private val modules = mapOf(
            "kubernetes" to "surefold-cluster-kubernetes",
            "ecs" to "surefold-cluster-aws",
            "cloudmap" to "surefold-cluster-aws",
        )

        /** Every way to join on the classpath, by name. */
        fun available(): Map<String, Joins> =
            ServiceLoader.load(Joins::class.java, Joins::class.java.classLoader).associateBy { it.name }

        /** The joining [name] builds from [options]; a name not on the classpath is refused, naming its module. */
        fun named(name: String, options: JoinOptions): Joining {
            val found = available()
            val joins = found[name] ?: throw IllegalArgumentException(
                modules[name]?.let { "join = $name needs $it on the classpath" }
                    ?: "join = $name is none of ${found.keys.sorted()}",
            )
            return joins.joining(options)
        }
    }
}

/**
 * What a [Joins] is given: the port this node listens on, how long a split must hold before it is broken, and its own
 * section of config as plain values, so `surefold-cluster` reads no config format. A missing or wrong value is refused
 * with the path it was read at.
 */
class JoinOptions(
    val port: Int,
    val stableAfter: Duration,
    private val values: Map<String, Any?> = emptyMap(),
    private val at: String = "",
) {
    fun string(key: String): String = stringOrNull(key) ?: throw IllegalArgumentException("${path(key)} is missing")

    fun stringOrNull(key: String): String? = when (val value = values[key]) {
        null -> null
        is String, is Number -> value.toString()
        else -> throw IllegalArgumentException("${path(key)} is not a string")
    }

    fun int(key: String, default: Int): Int =
        stringOrNull(key)?.let { it.toIntOrNull() ?: throw IllegalArgumentException("${path(key)} is not a number") }
            ?: default

    fun strings(key: String): List<String> = when (val value = values[key]) {
        is List<*> -> value.map { it.toString() }

        // `-Dpath.seeds.0=…` system properties arrive as an object keyed by index, which HOCON reads as a list.
        is Map<*, *> -> numbered(key, value)

        null -> throw IllegalArgumentException("${path(key)} is missing")

        else -> throw IllegalArgumentException("${path(key)} is not a list")
    }

    private fun numbered(key: String, value: Map<*, *>): List<String> {
        val indexed = value.entries.associate { (index, item) -> index.toString().toIntOrNull() to item.toString() }
        require(indexed.isNotEmpty() && null !in indexed.keys) { "${path(key)} is not a list" }
        return indexed.entries.sortedBy { it.key }.map { it.value }
    }

    /** A section of `name = value` pairs, as a label selector is written. */
    fun labels(key: String): Map<String, String> = when (val value = values[key]) {
        is Map<*, *> -> value.entries.associate { (name, label) -> name.toString() to label.toString() }
        null -> throw IllegalArgumentException("${path(key)} is missing")
        else -> throw IllegalArgumentException("${path(key)} is not a section")
    }

    private fun path(key: String) = if (at.isEmpty()) key else "$at.$key"
}

/** A fixed list of `host:port` seeds, and the majority keeping itself when they split. */
class StaticJoins : Joins {
    override val name = "static"

    override fun joining(options: JoinOptions): Joining {
        val seeds = options.strings("seeds").map(Node::at)
        require(seeds.isNotEmpty()) { "static.seeds is empty: a node needs somewhere to join through" }
        return Joining(
            Discovery { seeds },
            Downing.keepMajority(options.stableAfter),
        )
    }
}

/** Every address a DNS name resolves to, at this node's port unless `port` says otherwise. */
class DnsJoins : Joins {
    override val name = "dns"

    override fun joining(options: JoinOptions): Joining = Joining(
        Discovery.dns(options.string("name"), options.int("port", options.port)),
        Downing.keepMajority(options.stableAfter),
    )
}

/** The targets and ports of a DNS name's SRV records. */
class SrvJoins : Joins {
    override val name = "srv"

    override fun joining(options: JoinOptions): Joining =
        Joining(Discovery.srv(options.string("name")), Downing.keepMajority(options.stableAfter))
}
