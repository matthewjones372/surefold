package dev.surefold

import java.time.Duration
import java.time.Instant
import java.util.Collections

internal val annotations: SurefoldLocal<Map<String, String>> = surefoldLocal { emptyMap() }

internal val spans: SurefoldLocal<List<Span>> = surefoldLocal { emptyList() }

internal class Span(val name: String, val startedAt: Instant)

/** How long each span had been running, keyed by its name, for the line written at [at]. */
internal fun List<Span>.elapsedAt(at: Instant): Map<String, String> =
    associate { span -> "${span.name}_ms" to Duration.between(span.startedAt, at).toMillis().toString() }

/** Runs [block] with [pairs] on every line written inside it, this thread's forks included. */
fun <A> logAnnotated(vararg pairs: Pair<String, String>, block: () -> A): A =
    annotations.locally(annotations.get() + pairs, block)

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
