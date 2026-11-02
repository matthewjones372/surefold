package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigException

/** Where each section's block goes. Its level is the switch: there is no flag to turn the report off. */
private val logger: System.Logger = System.getLogger("surefold-app")

/**
 * What [section] read under [path], logged before anything is built on it — and before a refusal,
 * whose faults are easier to read beside the settings that were found.
 */
@PublishedApi
internal fun Section.logged(root: Config, path: String) {
    if (paths.isNotEmpty()) logger.log(System.Logger.Level.INFO) { root.reportOf(path, paths) }
}

/**
 * The block for one section: only the [paths] it read, in the order read, each masked if it was read
 * as a secret or [Secrets.default] would mask it. Over layers it says what each setting overrode; over
 * a `Config` written by hand, only where each came from.
 */
internal fun Config.reportOf(section: String, paths: List<PathRead>): String {
    val secrets = paths.filter { it.secret }.fold(Secrets.default) { secrets, read -> secrets.and(read.path) }
    val known = (layeredOrNull()?.origins(secrets) ?: origins(secrets)).associateBy { it.path }
    val lines = paths.flatMap { (path) ->
        known[path]?.let(::listOf)
            // An object read whole is its settings, each masked as it would be alone: rendered as one
            // value, the password inside it is printed under a name that says nothing.
            ?: known.values.filter { it.path.startsWith("$path.") }.ifEmpty { null }
            ?: listOf(
                when {
                    has(path) -> originOf(path, getValue(path), secrets)
                    else -> Origin(path, value = "", where = "not set", redacted = false)
                },
            )
    }
    return Origins(lines).report("surefold-app configuration: $section")
}

/** A path HOCON cannot parse is one the read recorded a fault for; the report says it is not set. */
private fun Config.has(path: String): Boolean =
    try {
        hasPath(path)
    } catch (_: ConfigException.BadPath) {
        false
    }
