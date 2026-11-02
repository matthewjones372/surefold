package io.github.matthewjones372.surefold.slf4j

import dev.surefold.LogLevel
import dev.surefold.LogLine
import dev.surefold.Logger
import org.slf4j.LoggerFactory
import org.slf4j.MDC

/**
 * surefold's lines, handed to whichever SLF4J backend the service has already configured.
 *
 * ```kotlin
 * val exit = logger.locally(Slf4jLogger()) { runApp(app) }
 * ```
 *
 * The annotations go into the MDC rather than onto the end of the message. `logAnnotated` binds a
 * pair that is carried across a fork, which an MDC cannot do by itself, and appending it to the text
 * would leave `%X{pet_id}`, a JSON encoder and every field search with nothing to read.
 *
 * Each line goes to the logger named after the class that wrote it, its [LogLine.origin], so a
 * backend can set a level per package. A [name] sends every line to that one logger instead, and a
 * line with no origin goes to `surefold`.
 */
class Slf4jLogger(name: String? = null) : Logger {

    // SLF4J caches loggers by name, so looking one up per line adds no cache of ours.
    private val named = name?.let(LoggerFactory::getLogger)

    override fun log(line: LogLine) {
        // Put back rather than cleared. The thread is one a pool may hand to something else next, so
        // nothing may carry over — and a key the service set itself is not this adapter's to drop.
        val before = MDC.getCopyOfContextMap()
        line.annotations.forEach { (key, value) -> MDC.put(key, value) }
        try {
            say(line, named ?: LoggerFactory.getLogger(line.origin ?: "surefold"))
        } finally {
            if (before == null) MDC.clear() else MDC.setContextMap(before)
        }
    }

    private fun say(line: LogLine, log: org.slf4j.Logger) = when (line.level) {
        LogLevel.Debug -> log.debug(line.message, line.cause)
        LogLevel.Info -> log.info(line.message, line.cause)
        LogLevel.Warn -> log.warn(line.message, line.cause)
        LogLevel.Error -> log.error(line.message, line.cause)
    }
}
