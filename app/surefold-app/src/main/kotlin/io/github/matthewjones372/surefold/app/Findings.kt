package io.github.matthewjones372.surefold.app

import dev.surefold.fail.fold
import kotlin.reflect.KType

/** Whether a finding stops a build. */
enum class Severity {
    WARN,
    FAIL,
}

/** A fault in the graph, and how much it matters. */
data class Finding(val severity: Severity, val error: WiringError)

/**
 * Every fault the graph can be asked about without running a recipe, worst first.
 *
 * [root] is what reachability is measured from; omitting it skips that check and nothing else, so a
 * module that is not a whole application can still be asked about its missing keys.
 */
fun Module.findings(root: KType? = null): List<Finding> {
    val faults = validate().fold({ it.toList() }, { emptyList() })
    val duplicates = shadows.map { WiringError.Duplicate(it.key, it.shadowed, it.wins) }
    val warnings = duplicates.map { Finding(Severity.WARN, it) }
    return (faults.map { Finding(Severity.FAIL, it) } + rootFindings(root) + warnings)
        .sortedByDescending { it.severity }
}

private fun Module.rootFindings(root: KType?): List<Finding> = when (root) {
    null -> emptyList()
    !in nodes -> listOf(Finding(Severity.FAIL, WiringError.NoRoot(root)))
    else -> forgotten(root).map { Finding(Severity.WARN, WiringError.Unreachable(it, nodes.getValue(it).site)) }
}

/**
 * The unreached nodes nothing else unreached depends on.
 *
 * A module left out of the graph takes everything under it with it, and naming all of them is one
 * edit reported as nine. The top of each unreached subtree is the line to change.
 */
private fun Module.forgotten(root: KType): List<KType> {
    val unreached = nodes.keys - reached(nodes, setOf(root), setOf(root))
    return unreached.filterNot { key ->
        unreached.any { other -> other != key && key in nodes.getValue(other).dependencies }
    }.sortedBy { it.toString() }
}

/** Every finding in the words the reader needs to fix it, or an empty string where there are none. */
fun List<Finding>.report(): String = when {
    isEmpty() -> ""
    else -> (listOf("surefold-app wiring") + map { it.lines() }).joinToString("\n\n")
}

private const val UNKNOWN = "(no site)"

private fun Finding.lines(): String {
    val word = when (severity) {
        Severity.FAIL -> "error"
        Severity.WARN -> "warning"
    }
    return when (val fault = error) {
        is WiringError.Missing ->
            "❯ $word: missing ${labelOf(fault.key)}\n❯     for ${at(labelOf(fault.neededBy), fault.site)}"

        is WiringError.Cycle ->
            "❯ $word: cycle ${fault.path.joinToString(" → ", transform = ::labelOf)}"

        is WiringError.NoRoot ->
            "❯ $word: nothing builds ${labelOf(fault.key)}, which the application starts from"

        is WiringError.Duplicate -> listOf(
            "❯ $word: ${labelOf(fault.key)} provided twice",
            "❯     ${at(fault.shadowed ?: UNKNOWN, "shadowed")}",
            "❯     ${at(fault.wins ?: UNKNOWN, "wins")}",
        ).joinToString("\n")

        is WiringError.Unreachable ->
            "❯ $word: nothing reaches ${at(labelOf(fault.key), fault.site)}"
    }
}
