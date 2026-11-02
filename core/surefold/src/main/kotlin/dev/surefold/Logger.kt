package dev.surefold

import java.lang.invoke.MethodHandles
import java.time.Instant
import java.util.ServiceLoader

enum class LogLevel { Debug, Info, Warn, Error }

/** One line, as a value: what a backend renders and what a test asserts on. */
data class LogLine(
    val level: LogLevel,
    val message: String,
    val at: Instant,
    val cause: Throwable? = null,
    val annotations: Map<String, String> = emptyMap(),
    /** The top-level class the line was written from. */
    val origin: String? = null,
)

fun interface Logger {
    fun log(line: LogLine)
}

/** Enough to see a service start. Anything more is an adapter's, and a dependency this module has not got. */
object StderrLogger : Logger {

    override fun log(line: LogLine) {
        val annotated = line.annotations.entries.joinToString("") { (key, value) -> " $key=$value" }
        val origin = line.origin?.let { "$it " }.orEmpty()
        System.err.println("${line.at} ${line.level.name.uppercase()} $origin${line.message}$annotated")
        line.cause?.printStackTrace(System.err)
    }
}

/** The logger a line goes to, and the one a fork inherits from its opener. */
val logger: SurefoldLocal<Logger> = surefoldLocal { discovered }

/**
 * The [Logger] a jar on the classpath registered, or [StderrLogger].
 *
 * So an adapter is a dependency and not a line in `main`: `surefold-slf4j` ships a service file, and a
 * service that puts it on the classpath has bound nothing and still logs where everything else does.
 * `logger.locally` overrides it, which is what a test does and what an application does where it
 * builds its own.
 *
 * Resolved once, because the initial value of a [SurefoldLocal] is asked for on every unbound read, and
 * a `ServiceLoader` scan per log line is a scan per log line. Loaded through this class's own loader
 * rather than the thread's: which thread a line is written on is exactly what surefold makes vary.
 */
private val discovered: Logger by lazy {
    firstRegistered("Logger", StderrLogger) {
        ServiceLoader.load(Logger::class.java, Logger::class.java.classLoader).firstOrNull()
    }
}

// Inline so that `lookupClass()` runs in the caller, which names it without walking the stack. That is
// the whole reason for the inlining, so NOTHING_TO_INLINE's performance argument does not apply.
@Suppress("NOTHING_TO_INLINE")
inline fun logDebug(message: String) = logAt(LogLevel.Debug, message, null, MethodHandles.lookup().lookupClass())

@Suppress("NOTHING_TO_INLINE")
inline fun logInfo(message: String) = logAt(LogLevel.Info, message, null, MethodHandles.lookup().lookupClass())

@Suppress("NOTHING_TO_INLINE")
inline fun logWarn(message: String) = logAt(LogLevel.Warn, message, null, MethodHandles.lookup().lookupClass())

@Suppress("NOTHING_TO_INLINE")
inline fun logError(message: String, cause: Throwable? = null) =
    logAt(LogLevel.Error, message, cause, MethodHandles.lookup().lookupClass())

/** A companion, a nested class, a lambda or an anonymous object reports the class that holds it. */
private val topLevel = object : ClassValue<String>() {
    override fun computeValue(type: Class<*>): String = generateSequence(type) { it.enclosingClass }.last().name
}

@PublishedApi
internal fun logAt(level: LogLevel, message: String, cause: Throwable?, caller: Class<*>) {
    val at = clock.get().now()
    val line = LogLine(level, message, at, cause, annotations.get() + spans.get().elapsedAt(at), topLevel.get(caller))
    logger.get().log(line)
}
