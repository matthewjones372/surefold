package dev.surefold.app.compiler

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.ExpressionCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.extensions.FirExtensionRegistrar

/** Registers the checkers against a session, which is what both the compiler and the IDE build. */
class SurefoldFirExtensionRegistrar(private val verbose: Boolean, private val test: Boolean = false) :
    FirExtensionRegistrar() {

    override fun ExtensionRegistrarContext.configurePlugin() {
        +{ session: FirSession -> SurefoldCheckers(session, verbose, test) }
    }
}

internal class SurefoldCheckers(session: FirSession, verbose: Boolean, test: Boolean) :
    FirAdditionalCheckersExtension(session) {

    override val declarationCheckers: DeclarationCheckers =
        object : DeclarationCheckers() {
            override val classCheckers: Set<FirClassChecker> = setOf(ApplicationChecker(verbose), ShutdownChecker())
        }

    override val expressionCheckers: ExpressionCheckers =
        object : ExpressionCheckers() {
            override val functionCallCheckers: Set<FirFunctionCallChecker> =
                setOf(HandRolledChecker(test), TransactChecker())

            override val variableAssignmentCheckers: Set<FirVariableAssignmentChecker> =
                setOf(TransactAssignmentChecker())
        }
}
