package dev.surefold.app.compiler

import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.CompilerConfigurationKey
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** What the plugin is called wherever the compiler names one: the CLI, and the Gradle plugin. */
const val PLUGIN_ID: String = "dev.surefold.app"

internal val VERBOSE = CompilerConfigurationKey.create<Boolean>("say which applications were found")

internal val TEST = CompilerConfigurationKey.create<Boolean>("this compilation is tests")

/**
 * The one option, which exists so that a build can prove the plugin is loaded at all.
 *
 * Whether a graph is sound is said by a diagnostic; whether anybody is looking is not something a
 * silent plugin can be asked, and "it compiled" is the same answer either way.
 */
@OptIn(ExperimentalCompilerApi::class)
class SurefoldCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = listOf(
        CliOption(
            optionName = "verbose",
            valueDescription = "true|false",
            description = "Reports every application the checker found, as a warning.",
            required = false,
        ),
        CliOption(
            optionName = "test",
            valueDescription = "true|false",
            description = "Whether this compilation is tests, where a different set of hand-rolled code is named.",
            required = false,
        ),
    )

    override fun processOption(option: AbstractCliOption, value: String, configuration: CompilerConfiguration) {
        when (option.optionName) {
            "verbose" -> configuration.put(VERBOSE, value.toBoolean())
            "test" -> configuration.put(TEST, value.toBoolean())
            else -> error("unknown option ${option.optionName}")
        }
    }
}

/**
 * The plugin, which is checkers and nothing else.
 *
 * Nothing is generated and nothing is lowered, so there is no IR extension here — and that is what
 * lets the IDE run the same checkers in the editor, where there is no backend to run.
 */
@OptIn(ExperimentalCompilerApi::class)
class SurefoldCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        FirExtensionRegistrarAdapter.registerExtension(
            SurefoldFirExtensionRegistrar(
                verbose = configuration[VERBOSE] ?: false,
                test = configuration[TEST] ?: false,
            ),
        )
    }
}
