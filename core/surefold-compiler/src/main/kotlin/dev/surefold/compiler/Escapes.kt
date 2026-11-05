package dev.surefold.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirPropertyChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.FirProperty
import org.jetbrains.kotlin.fir.expressions.FirAnonymousFunctionExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.types.ConeKotlinType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.type
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A `scope` whose block answers with a `Deferred`, `Channel` or `Scope`, or something holding one: the scope ends every
 * fork and channel it opened as it returns, so what the caller is handed is already over (spec 0041).
 */
internal class ReturnedScopeChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val called = expression.calleeReference.toResolvedCallableSymbol()?.callableId ?: return
        if (called.packageName != surefold || called.className != null || called.callableName !in scopes) return
        val block = expression.argumentList.arguments.lastOrNull() as? FirAnonymousFunctionExpression ?: return
        val answered = block.anonymousFunction.returnTypeRef.coneType
        val escaping = answered.holding() ?: return
        reporter.reportOn(
            expression.source,
            ForkDiagnostics.DEFERRED_ESCAPES_SCOPE,
            "${called.callableName} returns a ${escaping.shortClassName}, which the scope ends as it returns; " +
                "await it inside the block and return the value",
            context,
        )
    }
}

/**
 * A property, not a local, whose type is or holds a `Deferred`, `Channel` or `Scope`: it is read after the scope that
 * opened it has ended it, or from a thread the scope does not own (spec 0041).
 */
internal class StoredScopeChecker : FirPropertyChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(declaration: FirProperty) {
        if (declaration.isLocal) return
        val escaping = declaration.returnTypeRef.coneType.holding() ?: return
        reporter.reportOn(
            declaration.source,
            ForkDiagnostics.DEFERRED_ESCAPES_SCOPE,
            "${declaration.name} keeps a ${escaping.shortClassName} past the scope that ends it; " +
                "keep it in a local of the scope's block",
            context,
        )
    }
}

/** The scoped class this type is, or holds as a type argument at any depth. */
private fun ConeKotlinType.holding(): ClassId? =
    classId?.takeIf { it in scoped } ?: typeArguments.firstNotNullOfOrNull { it.type?.holding() }

private val surefold = FqName("dev.surefold")

private val scopes = setOf(Name.identifier("scope"))

private val scoped = setOf("Deferred", "Channel", "Scope").map { ClassId(surefold, Name.identifier(it)) }.toSet()
