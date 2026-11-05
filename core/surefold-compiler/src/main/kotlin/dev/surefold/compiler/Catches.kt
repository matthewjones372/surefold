package dev.surefold.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirTryExpressionChecker
import org.jetbrains.kotlin.fir.expressions.FirCatch
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirThrowExpression
import org.jetbrains.kotlin.fir.expressions.FirTryExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.resolve.fullyExpandedType
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.visitors.FirVisitorVoid
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * A catch-all around a call that fails or waits, which neither throws again nor puts the interrupt back (spec 0041).
 * `catch (e: Throwable)` takes `fail`'s own throwable, so a `fail` or `bind` inside never reaches its `attempt`; `catch
 * (e: Exception)` and `catch (e: InterruptedException)` take the interrupt an `await` is stopped with, so the scope
 * that sent it waits on a fork that carries on. `RuntimeException` takes neither, and is not judged.
 */
internal class EatenFailureChecker : FirTryExpressionChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirTryExpression) {
        val calls = Calls().also { expression.tryBlock.accept(it) }.found
        expression.catches.forEach { caught ->
            val type = caught.parameter.returnTypeRef.coneType.fullyExpandedType().classId
            val eaten =
                when (type) {
                    in throwables -> calls.filter { it in failing || it == awaiting }
                    in interrupted -> calls.filter { it == awaiting }
                    else -> emptyList()
                }
            if (eaten.isEmpty() || caught.handsOn()) return@forEach
            reporter.reportOn(
                caught.parameter.source,
                ForkDiagnostics.CATCH_EATS_RAISE,
                "catch (${caught.parameter.name}: ${type?.shortClassName}) takes what " +
                    eaten.distinct().joinToString(" and ") { it.callableName.asString() } +
                    " ends with and keeps it; rethrow it, call Thread.currentThread().interrupt(), or catch " +
                    "the exceptions you mean",
                context,
            )
        }
    }
}

/** Whether this catch throws again, or puts the thread's interrupt back, anywhere in its block. */
private fun FirCatch.handsOn(): Boolean {
    var handed = false
    block.accept(
        object : FirVisitorVoid() {
            override fun visitElement(element: FirElement) = element.acceptChildren(this)

            override fun visitThrowExpression(throwExpression: FirThrowExpression) {
                handed = true
            }

            override fun visitFunctionCall(functionCall: FirFunctionCall) {
                val called = functionCall.calleeReference.toResolvedCallableSymbol()?.callableId
                if (called == interrupt || called == nonFatalOrThrow) handed = true
                super.visitFunctionCall(functionCall)
            }
        },
    )
    return handed
}

/** The calls in a try block that fail or wait, leaving out the blocks of `async` calls, which run elsewhere. */
private class Calls : FirVisitorVoid() {
    val found = mutableListOf<CallableId>()

    override fun visitElement(element: FirElement) = element.acceptChildren(this)

    override fun visitFunctionCall(functionCall: FirFunctionCall) {
        val called = functionCall.calleeReference.toResolvedCallableSymbol()?.callableId
        if (called == async) return
        if (called != null && (called in failing || called == awaiting)) found += called
        super.visitFunctionCall(functionCall)
    }
}

private val surefold = FqName("dev.surefold")

private val fail = FqName("dev.surefold.fail")

private val async = CallableId(ClassId(surefold, Name.identifier("Scope")), Name.identifier("async"))

private val awaiting = CallableId(ClassId(surefold, Name.identifier("Deferred")), Name.identifier("await"))

private val failing =
    setOf(
        CallableId(ClassId(fail, Name.identifier("Fail")), Name.identifier("fail")),
        CallableId(fail, Name.identifier("bind")),
    )

private val interrupt =
    CallableId(ClassId(FqName("java.lang"), Name.identifier("Thread")), Name.identifier("interrupt"))

private val nonFatalOrThrow = CallableId(fail, Name.identifier("nonFatalOrThrow"))

private val throwables =
    setOf(
        ClassId(FqName("kotlin"), Name.identifier("Throwable")),
        ClassId(FqName("java.lang"), Name.identifier("Throwable")),
    )

private val interrupted =
    setOf(
        ClassId(FqName("java.lang"), Name.identifier("Exception")),
        ClassId(FqName("java.lang"), Name.identifier("InterruptedException")),
    )
