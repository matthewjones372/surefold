package dev.surefold.app.typesafe

import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import dev.surefold.app.Module
import dev.surefold.app.overriding
import dev.surefold.app.single
import java.nio.file.Path

/** What a service reads at start-up — what `ConfigFactory.load()` reads — with its layers kept for the report. */
fun loadedConfig(): Module = layered { layeredConfig() }

/** A document the assembly already parsed — the one a `choosing` read to pick its modules. */
fun configOf(config: Config): Module = layered { layeredConfigOf("configOf" to config) }

/** A document written where the test is, for a case that would rather not have a file at all. */
fun configOf(hocon: String): Module = layered { layeredConfigOf("configOf" to ConfigFactory.parseString(hocon)) }

/** A file on the classpath — `test.conf` beside the test, rather than in the service's own. */
fun configFromResource(name: String): Module =
    layered { layeredConfigOf(name to ConfigFactory.parseResources(name)) }

/** A file on disk, for a fixture a test writes and a container reads. */
fun configFromFile(path: Path): Module =
    layered { layeredConfigOf(path.toString() to ConfigFactory.parseFile(path.toFile())) }

/**
 * This graph with [hocon] on top of the configuration it already has.
 *
 * The two-key case, which is most of them: a test that wants a random port and a fast poll should not
 * have to restate the database, the broker and everything else the file says. [fallback] is what the
 * service itself would have read, and anything [hocon] does not mention comes from there. The graph's
 * `Config` is the merge of its [Layered], so replacing the layers replaces both — which is also why
 * the graph has to have come from one of the sources above.
 */
fun Module.overridingConfig(hocon: String, fallback: Layered = layeredConfig()): Module =
    overriding(
        single<Layered> { Layered(listOf(Layer("overrides", ConfigFactory.parseString(hocon))) + fallback.layers) },
    )

/** The same, over a document already merged, which is one layer under the overrides. */
fun Module.overridingConfig(hocon: String, fallback: Config): Module =
    overridingConfig(hocon, layeredConfigOf("fallback" to fallback))

/** Every source is its layers and their merge, so a report can say what a setting overrode. */
private fun layered(layers: () -> Layered): Module =
    single<Layered> { layers() } + single<Config, Layered> { layered -> layered.config }
