package dev.surefold.compiler

import org.jetbrains.kotlin.KtSourceElement
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirPropertyAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirValueParameterSymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.resolvedType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * What an `async` block does with its scope's things from outside it (spec 0041): it awaits or cancels a `Deferred`
 * another block opened (`AWAIT_ACROSS_FORK`), or fails through a `Fail` receiver it reached by label from outside
 * (`RAISE_ACROSS_FORK`). Either surfaces into a scope from a thread that scope does not own. Each `async` reads its own
 * block and stops at the `async` calls nested in it, which are read on their own.
 */
internal class ForkBoundaryChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        if (!expression.isAsync()) return
        val block = expression.argumentList.arguments.lastOrNull() as? FirAnonymousFunctionExpression ?: return
        val within = block.anonymousFunction.source ?: return
        val crossings = Crossings(within)
        block.anonymousFunction.body?.accept(crossings)
        crossings.found.forEach { (source, crossing) ->
            when (crossing) {
                is Crossing.Await ->
                    reporter.reportOn(
                        source,
                        ForkDiagnostics.AWAIT_ACROSS_FORK,
                        "${crossing.method} on ${crossing.name}, a Deferred from outside this async block, " +
                            "joins a fork " +
                            "this thread does not own; await ${crossing.name} in the scope's own block and pass the " +
                            "value in, or open this fork after ${crossing.name}.await()",
                        context,
                    )

                is Crossing.Raise ->
                    reporter.reportOn(
                        source,
                        ForkDiagnostics.RAISE_ACROSS_FORK,
                        "this@${crossing.label} fails a scope from a thread it does not own; fail through this async " +
                            "block's own scope, and let await carry the error out",
                        context,
                    )
            }
        }
    }
}

/** What one `async` block reaches across its boundary for. */
private sealed interface Crossing {
    data class Await(val name: String, val method: String) : Crossing

    data class Raise(val label: String) : Crossing
}

private class Crossings(private val within: KtSourceElement) : FirVisitorVoid() {
    val found = mutableListOf<Pair<KtSourceElement?, Crossing>>()

    override fun visitElement(element: FirElement) = element.acceptChildren(this)

    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        // A nested async is read by its own check, against its own block.
        if (functionCall.isAsync()) return
        val called = functionCall.calleeReference.toResolvedCallableSymbol()?.callableId
        if (called in joining) {
            val receiver = functionCall.explicitReceiver as? FirPropertyAccessExpression
            val variable = receiver?.calleeReference?.toResolvedVariableSymbol()
            val local = variable is FirLocalPropertySymbol || variable is FirValueParameterSymbol
            if (local && called != null && variable.source?.isInside() == false) {
                found += functionCall.source to Crossing.Await(variable.name.asString(), called.callableName.asString())
            }
        }
        super.visitFunctionCall(functionCall)
    }

    override fun visitThisReceiverExpression(thisReceiverExpression: FirThisReceiverExpression) {
        val reference = thisReceiverExpression.calleeReference
        val label = reference.labelName
        val owner = reference.boundSymbol?.source
        if (label != null && owner?.isInside() == false && thisReceiverExpression.resolvedType.classId in failing) {
            found += thisReceiverExpression.source to Crossing.Raise(label)
        }
        super.visitThisReceiverExpression(thisReceiverExpression)
    }

    private fun KtSourceElement.isInside(): Boolean = startOffset >= within.startOffset && endOffset <= within.endOffset
}

private fun FirFunctionCall.isAsync(): Boolean = calleeReference.toResolvedCallableSymbol()?.callableId == async

private val surefold = FqName("dev.surefold")

private val async = CallableId(ClassId(surefold, Name.identifier("Scope")), Name.identifier("async"))

private val deferred = ClassId(surefold, Name.identifier("Deferred"))

private val joining = setOf("await", "cancel").map { CallableId(deferred, Name.identifier(it)) }.toSet()

private val failing =
    setOf(
        ClassId(FqName("dev.surefold.fail"), Name.identifier("Fail")),
        ClassId(surefold, Name.identifier("Scope")),
    )
