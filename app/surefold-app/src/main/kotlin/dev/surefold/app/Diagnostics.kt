package dev.surefold.app

import java.io.File
import kotlin.reflect.KType

/**
 * The findings in the shape a build tool already knows how to make clickable.
 *
 * `e: file:///abs/Wiring.kt:92:1 message` is what the Kotlin compiler prints, which is why it is what this prints: the
 * IDE parses a build's output looking for exactly that, and turns the ones it finds into entries in the Build window
 * that jump to the line. A report only a person can read is a report they have to search their own source for.
 *
 * [sources] are the source directories the sites are relative to. A finding whose site names no file under any of them
 * — or which has no site at all, as a cycle does not — still gets a line, without a location, because a fault nobody
 * can link to is still a fault.
 *
 * [provided] is what the graph does build, which a missing key is compared against: `Tracer` missing from a graph
 * holding `Tracer!` is the report worth having.
 */
fun List<Finding>.diagnostics(sources: List<File>, provided: Set<KType> = emptySet()): String =
    joinToString("\n") { finding ->
        "${mark(finding)}${where(finding.error.site, sources)}${sentence(finding, provided)}"
    }

private fun mark(finding: Finding): String =
    when (finding.severity) {
        Severity.FAIL -> "e: "
        Severity.WARN -> "w: "
    }

/** The site as the URI the IDE's own parser expects, column included, or nothing. */
private fun where(site: String?, sources: List<File>): String {
    val path = site ?: return ""
    val line = path.substringAfterLast(':')
    val file = resolved(path.substringBeforeLast(':'), sources) ?: return ""
    return "file://${file.absolutePath}:$line:1 "
}

/**
 * The file a site names, looked for where it was written first and by its own name second.
 *
 * The fallback covers a source directory laid out by something other than the package — which is legal Kotlin, and
 * which the compiler's own `-Xno-package-directories-check` exists for.
 */
private fun resolved(path: String, sources: List<File>): File? =
    sources.map { File(it, path) }.firstOrNull { it.isFile }
        ?: sources.firstNotNullOfOrNull { root ->
            root.walkTopDown().firstOrNull { it.isFile && it.name == path.substringAfterLast('/') }
        }

/** Where the fault was written, for those that know. */
private val WiringError.site: String?
    get() =
        when (this) {
            is WiringError.Missing -> site

            is WiringError.Unreachable -> site

            // The one that wins is the line a reader would edit to stop the other being shadowed.
            is WiringError.Duplicate -> wins ?: shadowed

            is WiringError.Cycle,
            is WiringError.NoRoot -> null
        }

/** One line, because a diagnostic that wraps is two diagnostics to whatever reads the output. */
private fun sentence(finding: Finding, provided: Set<KType>): String =
    when (val fault = finding.error) {
        is WiringError.Missing ->
            "surefold-app: ${labelOf(fault.neededBy)} needs ${labelOf(fault.key)}, and nothing builds it" +
                alike(fault.key, provided)

        is WiringError.Cycle -> "surefold-app: cycle ${fault.path.joinToString(" -> ", transform = ::labelOf)}"

        is WiringError.NoRoot -> "surefold-app: nothing builds ${labelOf(fault.key)}, which the application starts from"

        is WiringError.Duplicate ->
            "surefold-app: ${labelOf(fault.key)} is provided twice; " +
                "this one wins over ${fault.shadowed?.let(::shortly) ?: "another"}"

        is WiringError.Unreachable ->
            "surefold-app: nothing reaches ${labelOf(fault.key)}, and it is built on every start"
    }

/**
 * A key the graph does build that reads the same as the missing one, named on the same line.
 *
 * A Java factory hands back a platform type, so a node keyed `Tracer!` matches nothing asking for a `Tracer` — and
 * "nothing builds Tracer" with a `Tracer` sitting in the graph is the least helpful true sentence a build can print.
 */
private fun alike(key: KType, provided: Set<KType>): String =
    provided
        .firstOrNull { it != key && plainly(it) == plainly(key) }
        ?.let { " (the graph has $it, which is not the same type)" }
        .orEmpty()
