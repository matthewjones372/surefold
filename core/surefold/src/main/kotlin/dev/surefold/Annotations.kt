package dev.surefold

import java.time.Duration
import java.time.Instant
import java.util.Collections

/**
 * One `logAnnotated` block's pairs over the blocks around it (spec 0282). Binding one copies nothing: the map a line
 * reads is built the first time something asks for it, and kept for every later line and every fork.
 */
internal class Annotated(
    private val pairs: Array<out Pair<String, String>>,
    private val outer: Annotated?,
    // Read from a fork's thread as well as the binder's. A plain field could hand a thread a reference to a map whose
    // contents it does not yet see; two threads building it at once build equal maps.
    @Volatile private var built: Map<String, String>? = null,
) {
    fun map(): Map<String, String> = built ?: SmallMap.of(outer?.map().orEmpty(), pairs).also { built = it }
}

internal val annotations: SurefoldLocal<Annotated?> = surefoldLocal { null }

/** The pairs every line written here carries, outermost first. */
internal fun boundAnnotations(): Map<String, String> = boundAnnotations(Bindings.snapshot())

/** [boundAnnotations] from a chain already in hand (spec 0284). */
internal fun boundAnnotations(head: Bound?): Map<String, String> = annotations.from(head)?.map().orEmpty()

internal val spans: SurefoldLocal<List<Span>> = surefoldLocal { emptyList() }

internal class Span(val name: String, val startedAt: Instant)

/** How long each span had been running, keyed by its name, for the line written at [at]. */
internal fun List<Span>.elapsedAt(at: Instant): Map<String, String> =
    associate { span -> "${span.name}_ms" to Duration.between(span.startedAt, at).toMillis().toString() }

/** Runs [block] with [pairs] on every line written inside it, this thread's forks included. */
fun <A> logAnnotated(vararg pairs: Pair<String, String>, block: () -> A): A =
    annotations.locally(Annotated(pairs, annotations.get()), block)

/** Runs [block] with [name] and how long it has been running on every line written inside it. */
fun <A> logSpan(name: String, block: () -> A): A =
    spans.locally(spans.get() + Span(name, clock.get().now()), block)

/** What was logged inside a [capturingLogs] block. */
class CapturedLogs internal constructor() : Logger {

    private val lines: MutableList<LogLine> = Collections.synchronizedList(mutableListOf())

    override fun log(line: LogLine) {
        lines += line
    }

    fun all(): List<LogLine> = synchronized(lines) { lines.toList() }
}

/** Binds a logger that keeps what [block] writes, rather than a backend nobody can assert on. */
fun <A> capturingLogs(block: (CapturedLogs) -> A): A {
    val captured = CapturedLogs()
    return logger.locally(captured) { block(captured) }
}
