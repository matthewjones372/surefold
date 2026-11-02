package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigOrigin
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigValue

/** What one setting resolved to, and where that came from. */
data class Origin(
    val path: String,
    val value: String,
    val where: String,
    val redacted: Boolean,
    val overrides: Overridden? = null,
)

/** A value a later layer replaced, kept so the report can say what it replaced. */
data class Overridden(val value: String, val where: String)

/**
 * The settings, and a [report] of them.
 *
 * A list with the rendering on it rather than an extension: `surefold-app` already has a
 * `List<Finding>.report`, and two extensions of one name on one receiver cannot both be imported
 * into a file — which the cookbook, compiled as a single file, finds immediately.
 */
class Origins internal constructor(private val settings: List<Origin>) : List<Origin> by settings {

    /** The settings in the words the reader needs, or an empty string for a document with none. */
    fun report(): String = report("surefold-app configuration")

    internal fun report(heading: String): String = when {
        settings.isEmpty() -> ""
        else -> (listOf(heading, "") + settings.flatMap { it.lines(widest()) }).joinToString("\n")
    }

    private fun widest(): Int = settings.maxOf { it.path.length }
}

/**
 * Every setting this document resolved to, in path order, because a report two runs apart is only
 * comparable if the order does not move.
 *
 * Runs against the merged document, so it says which layer won and not what it beat; [Layered] is
 * what answers that. Values [secrets] redacts are replaced by the mask and keep their origin, since
 * the origin is the half that says which file to go and edit.
 */
fun Config.origins(secrets: Secrets = Secrets.default): Origins =
    Origins(
        entrySet()
            .map { (path, value) -> originOf(path, value, secrets) }
            .sortedBy { it.path },
    )

internal fun originOf(path: String, value: ConfigValue, secrets: Secrets): Origin {
    val redacted = secrets.redacts(path)
    return Origin(
        path = path,
        value = if (redacted) MASK else value.rendered(),
        where = value.origin().where(),
        redacted = redacted,
    )
}

/** Concise, so a duration stays `30s` and a string loses the quotes Typesafe Config would print. */
internal fun ConfigValue.rendered(): String =
    render(ConfigRenderOptions.concise()).trim().removeSurrounding("\"")

/**
 * `file:line` where there is a file, and Typesafe Config's own words where there is not.
 *
 * `env variables` and `system properties` have no line to name, and the description is already the
 * sentence a reader wants.
 */
internal fun ConfigOrigin.where(): String =
    filename()?.let { "${it.substringAfterLast('/')}:${lineNumber()}" } ?: description()

private fun Origin.lines(pathWidth: Int): List<String> {
    val said = "${path.padEnd(pathWidth)}  ${value.padEnd(VALUE_COLUMN)}  $where"
    val was = overrides?.let {
        "${" ".repeat(pathWidth)}  overrides ${it.value} at ${it.where}"
    }
    return listOfNotNull(said, was)
}

private const val VALUE_COLUMN = 12
