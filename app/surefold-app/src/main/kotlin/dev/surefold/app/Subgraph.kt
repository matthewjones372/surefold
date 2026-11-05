package dev.surefold.app

import dev.surefold.fail.getOrElse
import kotlin.reflect.KType
import kotlin.reflect.typeOf

/** Only the nodes [A] is reached through, so a test starts four of them rather than forty. */
inline fun <reified A : Any> Module.subgraph(): Module = subgraph(typeOf<A>())

@PublishedApi
internal fun Module.subgraph(root: KType): Module {
    val kept = reached(nodes, setOf(root), setOf(root))
    return Module.of(
        nodes.filterKeys { it in kept },
        probes.filter { it.key in kept },
        shadows.filter { it.key in kept },
        pending,
    )
}

/**
 * [plus], refusing a key this module does not already hold: a fake under a new key fakes nothing. In a graph with a
 * choice, a key it does not hold may be one a branch brings, so it waits for one that does, and the start is refused if
 * no chosen branch brings it (spec 0136).
 */
fun Module.overriding(replacements: Module): Module {
    val unknown = replacements.nodes.keys - nodes.keys
    require(unknown.isEmpty() || nodes.values.any { it.choice != null }) {
        "overriding a key nothing provides: ${unknown.joinToString { labelOf(it) }}"
    }
    val known =
        Module.of(
            replacements.nodes.filterKeys { it !in unknown },
            replacements.probes.filter { it.key !in unknown },
            replacements.shadows,
        )
    return (this + known)
        .shadowing(shadows + replacements.shadows)
        .pendingFor(replacements.nodes.filterKeys { it in unknown })
}

/** Starts the whole graph for a test and gives it back afterwards, whatever the block did. */
inline fun <reified A : Any, B> testApp(module: Module, noinline block: (A) -> B): B =
    module.use(block).getOrElse { error(it.describe()) }

/**
 * [testApp], over only what [A] is reached through — the root read off the block, so the type is written once rather
 * than once here and once in a `subgraph<A>()` beside it.
 *
 * Not what [testApp] does by default, and a test that found out why is in this repository: a node nothing depends on is
 * not reached, so a probe asked of one stops being asked, and a background job nothing takes as a dependency stops
 * running. Cutting the graph down is worth saying out loud.
 */
inline fun <reified A : Any, B> testNode(module: Module, noinline block: (A) -> B): B =
    testApp(module.subgraph<A>(), block)

internal tailrec fun reached(
    nodes: Map<KType, Node>,
    frontier: Set<KType>,
    found: Set<KType>,
): Set<KType> {
    val next = frontier.flatMap { nodes[it]?.dependencies.orEmpty() }.toSet() - found
    return if (next.isEmpty()) found else reached(nodes, next, found + next)
}
