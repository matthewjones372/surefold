package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigException
import com.typesafe.config.ConfigUtil
import dev.surefold.app.Module
import dev.surefold.app.single
import dev.surefold.fail.Either
import dev.surefold.fail.NonEmptyList
import dev.surefold.fail.left
import dev.surefold.fail.right
import dev.surefold.fail.toNonEmptyListOrNull
import kotlin.time.Duration
import kotlin.time.toKotlinDuration

/** A path that could not be read, in Typesafe Config's own words — which name the file and the line. */
data class ConfigFault(val why: String)

/** A path a section asked for, from the document's root, and whether it was asked for as a [Secret]. */
data class PathRead(val path: String, val secret: Boolean)

/**
 * A read of a HOCON section that answers with every fault rather than the first.
 *
 * Nothing here wraps a value: [of] hands the caller the real [Config] and whatever it returns is the
 * value, so substitution, merging, `getDuration`, `getStringList`, `getMemorySize` and `getConfig`
 * all still work. What is added is the one thing Typesafe Config does not do — carrying on after a
 * missing path so that a bad file is one message rather than one deploy per fault.
 */
class Reading internal constructor(
    val config: Config,
    private val at: String? = null,
    private val read: MutableMap<String, Boolean> = linkedMapOf(),
) {

    internal val faults: MutableList<ConfigFault> = mutableListOf()

    /**
     * Every path a leaf asked for, nested sections' included, in the order asked — what the report of
     * this section prints, and all it prints. A path read through [of] without one is not here.
     */
    val paths: List<PathRead> get() = read.map { (path, secret) -> PathRead(path, secret) }

    /**
     * [read] against the section, or [absent] where it threw — with the exception's own message kept,
     * because it names the origin and Typesafe Config words it better than this could.
     */
    fun <A> of(absent: A, read: Config.() -> A): A =
        try {
            config.read()
        } catch (missing: ConfigException) {
            faults += ConfigFault(missing.message ?: missing.toString())
            absent
        }

    /** [of], with the [path] [read] reads recorded, for a leaf this class has no reader of its own for. */
    fun <A> of(path: String, absent: A, read: Config.() -> A): A = recorded(path, secret = false) { of(absent, read) }

    fun string(path: String): String = of(path, "") { getString(path) }

    fun int(path: String): Int = of(path, 0) { getInt(path) }

    fun long(path: String): Long = of(path, 0) { getLong(path) }

    fun boolean(path: String): Boolean = of(path, false) { getBoolean(path) }

    fun strings(path: String): List<String> = of(path, emptyList()) { getStringList(path) }

    /**
     * A string that will not print itself.
     *
     * For the secret a name match cannot find — a password inside a URL, a token under a name that
     * says nothing. What comes back masks itself in a log line, an exception and a data class's
     * `toString` alike; `Secrets.and(path)` is the other half, and redacts it in a report.
     */
    fun secret(path: String): Secret = recorded(path, secret = true) { Secret(of("") { getString(path) }) }

    /** HOCON's own duration — `30s`, `5 minutes` — as `kotlin.time`'s, which is what surefold takes. */
    fun duration(path: String): Duration = of(path, Duration.ZERO) { getDuration(path).toKotlinDuration() }

    /** HOCON's own size — `512M`, `2 GiB` — in bytes. */
    fun bytes(path: String): Long = of(path, 0) { getMemorySize(path).toBytes() }

    /** A nested section, read the same way. Its faults are this one's. */
    fun <A> section(path: String, block: Reading.() -> A): A {
        val nested = Reading(of(config.root().toConfig()) { getConfig(path) }, full(path), read)
        // In a finally rather than after the call: a constructor that validates one of the discarded
        // values throws past it, and the faults that produced those values are the answer wanted.
        try {
            return nested.block()
        } finally {
            faults += nested.faults
        }
    }

    private fun full(path: String): String = at?.let { "$it.$path" } ?: path

    /**
     * Secret if any read of it was: a value masked once and printed once has been printed. Kept as
     * HOCON writes it back, so `"host"` and `host` are one setting; a path HOCON cannot parse is kept
     * as given, and its read records the fault.
     */
    private fun <A> recorded(path: String, secret: Boolean, value: () -> A): A {
        read.merge(canonical(full(path)), secret, Boolean::or)
        return value()
    }

    private fun canonical(path: String): String =
        try {
            ConfigUtil.joinPath(ConfigUtil.splitPath(path))
        } catch (_: ConfigException.BadPath) {
            path
        }
}

/**
 * Reads [block] against this section. A failed read records the fault and answers with a value the
 * result is discarded with, so the block runs to the end and names everything that is wrong. A
 * constructor that validates its arguments may throw on those discarded values; that throw is caught
 * and the faults are what comes back, because they are what has to be fixed first.
 */
fun <A> Config.reading(block: Reading.() -> A): Either<NonEmptyList<ConfigFault>, A> {
    val reading = Reading(this)
    val value = reading.readOrFaulted(block)
    return reading.faults.toNonEmptyListOrNull()?.left()
        ?: checkNotNull(value) { "a read that threw recorded no fault, and was rethrown" }.value.right()
}

/**
 * The section at [path] as a node, read by the module that needs it.
 *
 * The module owns its own settings: one added later brings its reading with it rather than editing a
 * root type that has to know about every section. A section that cannot be read refuses the start,
 * naming every fault at once.
 */
inline fun <reified A : Any> config(path: String, noinline read: Reading.() -> A): Module =
    single { root: Config ->
        val section = root.sectionOf(path, read)
        section.logged(root, path)
        if (section.faults.isEmpty()) section.value as A else refuse(section.why)
    }

@Deprecated("Named config, which is what it reads", ReplaceWith("config(path, read)"))
inline fun <reified A : Any> configured(path: String, noinline read: Reading.() -> A): Module =
    config(path, read)

/**
 * The section at [path], read where the graph is assembled, so a setting can pick between modules.
 *
 * A setting like `useCache` does not configure a repository; it chooses between two. [config] cannot
 * answer that, because it makes the section a node and the choice has to be made before any node is
 * built — surefold-app builds every node in the graph, so a branch left in it starts its resources too.
 * [A] is still a node afterwards, exactly as [config] leaves it, so nothing reads the path twice.
 *
 * A choice is not a node, so a fault cannot refuse at the choice itself: the branch is picked from
 * the values [Reading] discards and the node for [A] carries the refusal, which is the message and
 * the exit code [config] would have given. Where the read throws instead — a constructor validating
 * one of those discarded values — there is nothing to pick with, and the faults are thrown here.
 */
inline fun <reified A : Any> Config.choosing(
    path: String,
    noinline read: Reading.() -> A,
    chosen: (A) -> Module,
): Module {
    val section = sectionOf(path, read)
    val why = section.why
    val settings = checkNotNull(section.value) { "surefold-app: $path could not be read: $why" } as A
    // Logged as the graph starts rather than here, where the assembly is still being written down.
    return chosen(settings) + single<A> {
        section.logged(this@choosing, path)
        if (why.isEmpty()) settings else refuse(why)
    }
}

/** What a section read and what it could not, which [reading] keeps apart because it answers with one. */
@PublishedApi
internal class Section(val value: Any?, val faults: List<ConfigFault>, val paths: List<PathRead>) {

    val why: String get() = faults.joinToString("; ") { fault -> fault.why }
}

@PublishedApi
internal fun <A> Config.sectionOf(path: String, read: Reading.() -> A): Section {
    val reading = Reading(this)
    val value = reading.readOrFaulted { section(path, read) }
    return Section(value?.value, reading.faults.toList(), reading.paths)
}

/** A value read, boxed so that a read answering `null` is told apart from one that threw. */
private class Read<A>(val value: A)

/**
 * [block]'s value, or null where it threw after a fault was recorded: the throw came from a discarded value,
 * and the faults are the answer. A throw with no fault recorded is a bug of the caller's, and is rethrown.
 */
@Suppress("TooGenericExceptionCaught")
private fun <A> Reading.readOrFaulted(block: Reading.() -> A): Read<A>? =
    try {
        Read(block())
    } catch (thrown: Throwable) {
        if (faults.isEmpty()) throw thrown
        null
    }
