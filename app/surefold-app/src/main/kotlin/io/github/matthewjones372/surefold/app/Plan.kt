package io.github.matthewjones372.surefold.app

import dev.surefold.fail.Either
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.left
import dev.surefold.fail.map
import dev.surefold.fail.mapLeft
import dev.surefold.fail.nonEmptyListOf
import dev.surefold.fail.right
import dev.surefold.fail.toNonEmptyListOrNull
import kotlin.reflect.KType

/** Why a graph cannot be built. */
sealed class WiringError {

    /** No module provides [key], and [neededBy] asked for it, where [site] was written. */
    data class Missing(val key: KType, val neededBy: KType, val site: String? = null) : WiringError()

    /** One cycle, each key needing the next, ending where it began. */
    data class Cycle(val path: List<KType>) : WiringError()

    /** Two modules both provide [key]; the node written at [shadowed] lost to the one at [wins]. */
    data class Duplicate(val key: KType, val shadowed: String?, val wins: String?) : WiringError()

    /** [key] is built on every start and no root reaches it. */
    data class Unreachable(val key: KType, val site: String?) : WiringError()

    /** The application starts from [key], and nothing in the graph builds it. */
    data class NoRoot(val key: KType) : WiringError()
}

/** The order a graph starts in: a layer's nodes depend only on the layers before it. */
class Plan internal constructor(val layers: List<List<KType>>)

/** The graph as mermaid, one edge per dependency. */
fun Module.render(): String {
    val keys = (nodes.keys + nodes.values.flatMap { it.dependencies }).distinct().sortedBy { it.toString() }
    val declarations = keys.map { "    ${idOf(it)}[${labelOf(it)}]" }
    val edges = nodes.values.sortedBy { it.key.toString() }.flatMap { node ->
        node.dependencies.sortedBy { it.toString() }.map { "    ${idOf(it)} --> ${idOf(node.key)}" }
    }
    return (listOf("graph TD") + declarations + edges).joinToString("\n")
}

/** Every fault in the graph, or the order it starts in. Runs no recipe. */
fun Module.validate(): Either<NonEmptyList<WiringError>, Plan> {
    val missing = nodes.values.flatMap { node ->
        node.dependencies
            .filterNot { nodes.containsKey(it) || it in runtimeProvided }
            .map { WiringError.Missing(it, node.key, node.site) }
    }

    // Every missing key at once: a graph is usually short of a module, not of one node.
    return missing.toNonEmptyListOrNull()?.left()
        ?: layers(nodes.mapValues { (_, node) -> node.dependencies.toSet() - runtimeProvided }, emptyList())
            .mapLeft { nonEmptyListOf(WiringError.Cycle(cycleIn(it))) }
            .map(::Plan)
}

/** The errors grouped by the key that is missing, with what asked for it under each. */
fun NonEmptyList<WiringError>.report(): String = report(emptySet())

/**
 * The report, with a key that reads the same as a missing one named beside it.
 *
 * A Java factory hands back a platform type, so `single { sdk: Sdk -> sdk.getTracer("app") }` builds
 * a node keyed `Tracer!` that nothing asking for a `Tracer` will ever match — and "missing Tracer"
 * with a `Tracer` sitting in the graph is the least helpful true sentence a build can print.
 */
fun NonEmptyList<WiringError>.report(provided: Set<KType>): String {
    val missing = filterIsInstance<WiringError.Missing>()
        .groupBy { it.key }
        .entries
        .sortedBy { (key, _) -> key.toString() }
        .map { (key, asked) ->
            val consumers = asked.map { "❯     for ${at(labelOf(it.neededBy), it.site)}" }.sorted()
            val alike = provided.firstOrNull { it != key && plainly(it) == plainly(key) }
            val nearly = alike?.let { listOf("❯     the graph has $it, which is not the same type") }.orEmpty()
            (listOf("❯ missing ${labelOf(key)}") + consumers + nearly).joinToString("\n")
        }

    val cycles = filterIsInstance<WiringError.Cycle>()
        .map { cycle -> "❯ cycle ${cycle.path.joinToString(" → ", transform = ::labelOf)}" }

    return (listOf("surefold-app wiring error") + missing + cycles).joinToString("\n\n")
}

/** A name and where it was written, padded so a column of them lines up. */
internal fun at(name: String, site: String?): String =
    if (site == null) name else name.padEnd(SITE_COLUMN) + shortly(site)

private const val SITE_COLUMN = 24

/** The label without what makes a platform type or a nullable one read differently. */
internal fun plainly(key: KType): String = labelOf(key).trimEnd('?', '!')

/** Kahn's algorithm, a layer at a time; what is left when nothing is ready holds the cycle. */
private tailrec fun layers(
    remaining: Map<KType, Set<KType>>,
    planned: List<List<KType>>,
): Either<Map<KType, Set<KType>>, List<List<KType>>> {
    val ready = remaining.filterValues { it.isEmpty() }.keys.sortedBy { it.toString() }
    return when {
        remaining.isEmpty() -> planned.right()

        ready.isEmpty() -> remaining.left()

        else -> layers(
            (remaining - ready.toSet()).mapValues { (_, needs) -> needs - ready.toSet() },
            planned + listOf(ready),
        )
    }
}

/** Every key left needs another that is also left, so following one far enough repeats. */
private fun cycleIn(stalled: Map<KType, Set<KType>>): List<KType> =
    walk(stalled, stalled.keys.sortedBy { it.toString() }.first(), emptyList())

private tailrec fun walk(graph: Map<KType, Set<KType>>, at: KType, seen: List<KType>): List<KType> =
    when (at) {
        in seen -> seen.dropWhile { it != at } + at
        else -> walk(graph, graph.getValue(at).sortedBy { it.toString() }.first(), seen + at)
    }
