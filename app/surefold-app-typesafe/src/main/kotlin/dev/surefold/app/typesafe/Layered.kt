package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigResolveOptions
import java.lang.ref.WeakReference

/** One document in the stack, under the name a reader knows it by. */
class Layer internal constructor(val name: String, val config: Config)

/**
 * The documents a service reads, kept apart.
 *
 * `withFallback` keeps the winning value and forgets the one it beat, so a merged [Config] cannot say
 * what a setting overrode — the same thing `Module.plus` does to a shadowed node, and answered the
 * same way. [layers] are in precedence order, the first winning.
 */
class Layered internal constructor(val layers: List<Layer>) {

    /**
     * The merge, resolved once over the whole stack, because a substitution may cross a layer. It
     * remembers these layers, so a section read from it can report what each setting overrode.
     */
    val config: Config by lazy {
        val merged = layers.map { it.config }.reduceOrNull { above, below -> above.withFallback(below) }
        (merged?.resolve() ?: ConfigFactory.empty()).also { remember(it, this) }
    }
}

/**
 * Each merge [Layered.config] has handed out, beside the stack it came from.
 *
 * A section depends on `Config` alone, so one written by hand still starts; the layers ride beside the
 * merge for the report rather than as a second node it would have to require. Beside it, not wrapped
 * round it: `withFallback` casts its argument to Typesafe's own `Config`, so a wrapper breaks the first
 * merge it is handed to. Found by identity, because Typesafe's `equals` ignores origins and two stacks
 * can merge to equal documents. Both held weakly: a single layer with nothing to resolve is its own
 * merge, so a strong hold on either would keep the other.
 */
private class Merge(config: Config, layered: Layered) {
    val config = WeakReference(config)
    val layered = WeakReference(layered)
}

private val merges = mutableListOf<Merge>()

private fun remember(config: Config, layered: Layered) = synchronized(merges) {
    merges.removeAll { it.config.get() == null }
    merges += Merge(config, layered)
}

/** The stack this merge came from, or null for a `Config` written by hand. */
internal fun Config.layeredOrNull(): Layered? =
    synchronized(merges) { merges.lastOrNull { it.config.get() === this }?.layered?.get() }

/**
 * What `ConfigFactory.load()` reads, as layers rather than as the merge of them, in its order: the
 * `CONFIG_FORCE_` variables when `-Dconfig.override_with_env_vars` asks for them, system properties,
 * the application layer, and the reference.
 *
 * Not `defaultReference()`, which merges system properties into the reference before handing it
 * over: a report built on that says `reference.conf` holds a value that came from `-D`.
 */
fun layeredConfig(): Layered = Layered(
    listOfNotNull(environmentOverrides(), "system properties" to ConfigFactory.systemProperties())
        .plus(application())
        .plus("reference.conf" to ConfigFactory.defaultReferenceUnresolved())
        .map { (name, config) -> Layer(name, config) },
)

/** `defaultOverrides()` merges these into the system properties, which would credit `-D` with an env var. */
private fun environmentOverrides(): Pair<String, Config>? =
    if (System.getProperty("config.override_with_env_vars").toBoolean()) {
        "environment overrides" to ConfigFactory.systemEnvironmentOverrides()
    } else {
        null
    }

/**
 * `defaultApplication()`, split where it merges: a strategy or a `config.file`, `.resource` or `.url`
 * replaces the whole layer, and otherwise `application.conf` beats `.json`, which beats `.properties`.
 */
private fun application(): List<Pair<String, Config>> {
    val strategy = System.getProperty("config.strategy")
    val replacement = REPLACEMENTS.firstNotNullOfOrNull { System.getProperty(it) }
    return when {
        strategy != null -> listOf(strategy to ConfigFactory.defaultApplication())
        replacement != null -> listOf(replacement to ConfigFactory.parseApplicationReplacement().get())
        else -> APPLICATION.map { name -> name to ConfigFactory.parseResources(name) }
    }
}

private val REPLACEMENTS = listOf("config.resource", "config.file", "config.url")

private val APPLICATION = listOf("application.conf", "application.json", "application.properties")

/** The stack written out, for a test that would rather not have three files. Highest precedence first. */
fun layeredConfigOf(vararg layers: Pair<String, Config>): Layered =
    Layered(layers.map { (name, config) -> Layer(name, config) })

/**
 * Every setting the stack resolved to, and for one that more than one layer supplied, what it beat.
 *
 * Each layer is resolved against the whole merged document rather than against itself, which is what
 * actually happened to it — a layer holding `${petshop.db.name}` for a name another layer supplies
 * cannot be read on its own. Unresolved is allowed rather than fatal: a report that refuses to
 * explain a broken file is the opposite of what it is for.
 */
fun Layered.origins(secrets: Secrets = Secrets.default): Origins {
    val merged = config
    val resolved = layers.map { Layer(it.name, it.config.resolvedWithin(merged)) }
    return Origins(
        merged.origins(secrets).map { origin -> origin.copy(overrides = resolved.beaten(origin, secrets)) },
    )
}

/**
 * Grafted into the merge and resolved there, not `resolveWith(merged)`: Typesafe Config throws
 * `BugOrBroken` resolving a delayed merge, `a = "x"` then `a = ${?ENV}`, against any document but its own.
 */
private fun Config.resolvedWithin(merged: Config): Config =
    merged.withValue(GRAFT, root()).resolve(LENIENT).getConfig(GRAFT)

/** The first layer under the winner that also held the path, which is the value the merge dropped. */
private fun List<Layer>.beaten(origin: Origin, secrets: Secrets): Overridden? =
    filter { it.config.hasPathOrNull(origin.path) }
        .drop(1)
        .firstOrNull()
        ?.let { layer ->
            // A null beaten is still a value beaten, and getValue throws on one rather than return it.
            val value = layer.config.takeUnless { it.getIsNull(origin.path) }?.getValue(origin.path)
            // Masked by the winner's own verdict: a report that hides the new password and prints the
            // old one has published a secret that is probably still in use somewhere.
            Overridden(
                if (secrets.redacts(origin.path)) MASK else value?.rendered() ?: "null",
                value?.origin()?.where() ?: layer.name,
            )
        }

private const val GRAFT = "surefold-layered-origins"

private val LENIENT: ConfigResolveOptions = ConfigResolveOptions.defaults().setAllowUnresolved(true)
