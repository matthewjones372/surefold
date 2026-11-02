package dev.surefold.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirClassChecker
import org.jetbrains.kotlin.fir.declarations.DirectDeclarationsAccess
import org.jetbrains.kotlin.fir.declarations.FirClass
import org.jetbrains.kotlin.fir.declarations.FirNamedFunction
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

private val app = FqName("dev.surefold.app")
private val surefoldApp = ClassId(app, Name.identifier("SurefoldApp"))
private val appScope = ClassId(app, Name.identifier("AppScope"))

private val parks = setOf("block", "join", "await")

/**
 * A `SurefoldApp.run` that parks on its root and never on `awaitShutdown` (spec 0132). The release that stops the
 * root runs after `run` returns, so such a `run` returns only when the JVM's grace period ends it.
 */
internal class ShutdownChecker : FirClassChecker(MppCheckerKind.Common) {

    @OptIn(DirectDeclarationsAccess::class)
    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirClass) {
        if (declaration.superTypeRefs.none { it.coneType.classId == surefoldApp }) return
        val run = declaration.declarations.filterIsInstance<FirNamedFunction>()
            .firstOrNull { it.name.asString() == "run" && it.receiverParameter?.typeRef?.coneType?.classId == appScope }
            ?: return
        val calls = Calls().also { run.body?.accept(it) }
        if (calls.awaitsShutdown) return
        calls.parked.forEach { parked ->
            val said = parked.named()
            reporter.reportOn(parked.source, SurefoldDiagnostics.SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN, said, context)
        }
    }
}

private class Calls : FirVisitorVoid() {
    var awaitsShutdown = false
    val parked = mutableListOf<FirFunctionCall>()

    override fun visitElement(element: FirElement) {
        element.acceptChildren(this)
    }

    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        val name = functionCall.calleeReference.name.asString()
        if (name == "awaitShutdown") awaitsShutdown = true
        if (name in parks && functionCall.argumentList.arguments.isEmpty()) parked += functionCall
        functionCall.acceptChildren(this)
    }
}

private fun FirFunctionCall.named(): String = "${calleeReference.name}()"
