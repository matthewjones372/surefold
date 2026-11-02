package dev.surefold.compiler

import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.compiler.plugin.AbstractCliOption
import org.jetbrains.kotlin.compiler.plugin.CommandLineProcessor
import org.jetbrains.kotlin.compiler.plugin.CompilerPluginRegistrar
import org.jetbrains.kotlin.compiler.plugin.ExperimentalCompilerApi
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrarAdapter

/** What the plugin is called wherever the compiler names one: the CLI, and the Gradle plugin. */
const val PLUGIN_ID: String = "dev.surefold.compiler"

/** The plugin takes no options; the compiler still asks a plugin it loads by id for a processor. */
@OptIn(ExperimentalCompilerApi::class)
class ForkCommandLineProcessor : CommandLineProcessor {

    override val pluginId: String = PLUGIN_ID

    override val pluginOptions: Collection<AbstractCliOption> = emptyList()
}

/**
 * The plugin, which is checkers and nothing else (spec 0041): nothing is generated, so the editor runs the same
 * checkers as the compiler.
 */
@OptIn(ExperimentalCompilerApi::class)
class ForkCompilerPluginRegistrar : CompilerPluginRegistrar() {

    override val pluginId: String = PLUGIN_ID

    override val supportsK2: Boolean = true

    override fun ExtensionStorage.registerExtensions(configuration: CompilerConfiguration) {
        // Checkers loaded into a compiler they were not built for would fail out of sight: the build says so instead.
        if (!builtForThisCompiler()) {
            configuration.get(CommonConfigurationKeys.MESSAGE_COLLECTOR_KEY)
                ?.report(CompilerMessageSeverity.WARNING, versionMismatch())
            return
        }
        FirExtensionRegistrarAdapter.registerExtension(ForkFirExtensionRegistrar())
    }
}

/** Registers the checkers against a session, which is what both the compiler and the IDE build. */
class ForkFirExtensionRegistrar : FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +::ForkCheckers
    }
}

internal class ForkCheckers(session: FirSession) : FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val propertyCheckers: Set<FirPropertyChecker> = setOf(StoredScopeChecker())
    }

    override val expressionCheckers: ExpressionCheckers = object : ExpressionCheckers() {
        override val functionCallCheckers: Set<FirFunctionCallChecker> =
            setOf(ReturnedScopeChecker(), ForkBoundaryChecker())

        override val tryExpressionCheckers: Set<FirTryExpressionChecker> = setOf(EatenFailureChecker())
    }
}
