package dev.surefold.app

import kotlin.reflect.KType

internal class Node(
    val key: KType,
    val dependencies: List<KType>,
    val site: String?,
    val build: Wiring.(List<Any>) -> Any,
    /** Set on the node a choice stands in the graph as; it is expanded, never built. */
    val choice: Choice? = null,
)

/** What a choice does once its dependencies are built: answer the module to build in its place. */
internal class Choice(val pick: (List<Any>) -> Module)

/** A key two modules both provided, and where each was written. */
internal class Shadow(val key: KType, val shadowed: String?, val wins: String?)

/** Recipes, keyed by the type each one builds. */
class Module private constructor(
    internal val nodes: Map<KType, Node>,
    internal val probes: List<Probe>,
    internal val shadows: List<Shadow> = emptyList(),
    /** Overrides for keys only a choice's branch brings, used when a chosen branch brings them (spec 0136). */
    internal val pending: Map<KType, Node> = emptyMap(),
) {

    /** [other]'s node wins wherever the two share a key, and takes the probes of that key with it. */
    operator fun plus(other: Module): Module =
        Module(
            nodes + other.nodes,
            probes.filterNot { it.key in other.nodes.keys } + other.probes,
            // Kept rather than discarded: the merge cannot tell a deliberate override from a typo, and
            // only `overriding` knows which it was.
            shadows + other.shadows + collisionsWith(other),
            pending + other.pending,
        )

    private fun collisionsWith(other: Module): List<Shadow> =
        other.nodes.keys.filter { it in nodes }
            .map { Shadow(it, nodes.getValue(it).site, other.nodes.getValue(it).site) }

    /**
     * The same module, shadowing exactly what [kept] did.
     *
     * Named by what survives rather than by the keys to drop: a base module that already shadowed a
     * key by accident keeps saying so, and only the collisions this merge introduced are forgiven.
     */
    internal fun shadowing(kept: List<Shadow>): Module = Module(nodes, probes, kept, pending)

    /** The same module, with [replacements] waiting for a chosen branch that brings their keys. */
    internal fun pendingFor(replacements: Map<KType, Node>): Module = Module(
        nodes, probes, shadows,
        pending + replacements,
    )

    internal companion object {
        fun of(node: Node): Module = Module(mapOf(node.key to node), emptyList())

        fun of(
            nodes: Map<KType, Node>,
            probes: List<Probe>,
            shadows: List<Shadow> = emptyList(),
            pending: Map<KType, Node> = emptyMap(),
        ): Module = Module(nodes, probes, shadows, pending)
    }
}

// Every lower-case run, so a generic argument loses its packages too.
private val qualifiers = Regex("""\b[a-z][A-Za-z0-9_]*(\.[a-z][A-Za-z0-9_]*)*\.""")

/**
 * What a `KType` renders as where kotlin-reflect is not on the classpath, once per type argument.
 *
 * This module depends on it nowhere else, and a report is no place to start: the name is still
 * right, and a build that prints "Pump (Kotlin reflection is not available) needs DataSource
 * (Kotlin reflection is not available)" has buried the sentence it came to say. Without it a nested
 * class also renders by its JVM name, `Outer$Inner`, so the `$` goes back to the `.` its source wrote.
 */
private val unavailable = Regex(""" \(Kotlin reflection is not available\)""")

internal fun labelOf(key: KType): String =
    qualifiers.replace(unavailable.replace(key.toString(), "").replace('$', '.'), "")

internal fun idOf(key: KType): String = labelOf(key).replace(Regex("[^A-Za-z0-9]"), "_")

@PublishedApi
internal fun module(
    key: KType,
    dependencies: List<KType>,
    build: Wiring.(List<Any>) -> Any,
): Module = Module.of(Node(key, dependencies, callSite(), build))
