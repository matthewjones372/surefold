package dev.surefold.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirLiteralExpression
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/** Calls that hand-roll something surefold ships, each named where it is written (spec 0132). */
internal class HandRolledChecker(private val test: Boolean) : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val called = symbol.callableId ?: return
        if (test && called in sleeps) {
            reporter.reportOn(
                expression.source,
                SurefoldDiagnostics.SUREFOLD_SLEEP_IN_TEST,
                said(symbol, called),
                context,
            )
        }
        if (!test && schedules(symbol, called)) {
            reporter.reportOn(
                expression.source,
                SurefoldDiagnostics.SUREFOLD_SCHEDULER_BY_HAND,
                said(symbol, called),
                context,
            )
        }
        if (!test && called == readingString) {
            val path = (expression.argumentList.arguments.firstOrNull() as? FirLiteralExpression)?.value as? String
            if (path != null && path.substringAfterLast('.').lowercase() in secretNames) {
                reporter.reportOn(expression.source, SurefoldDiagnostics.SUREFOLD_SECRET_AS_STRING, path, context)
            }
        }
    }
}

private val sleeps = setOf(
    CallableId(ClassId(FqName("java.lang"), Name.identifier("Thread")), Name.identifier("sleep")),
    CallableId(ClassId(FqName("java.util.concurrent"), Name.identifier("TimeUnit")), Name.identifier("sleep")),
)

private val readingString = CallableId(
    ClassId(FqName("dev.surefold.app.typesafe"), Name.identifier("Reading")),
    Name.identifier("string"),
)

/**
 * `Secrets.default`'s names: the checker cannot depend on surefold-app-typesafe, so SecretNamesSuite keeps them equal.
 */
private val secretNames: Set<String> = setOf(
    "password",
    "passphrase",
    "secret",
    "secretkey",
    "token",
    "apitoken",
    "accesstoken",
    "key",
    "apikey",
    "accesskey",
    "privatekey",
    "credential",
    "credentials",
)

private fun schedules(symbol: FirCallableSymbol<*>, called: CallableId): Boolean =
    called in schedulerFactories || (symbol is FirConstructorSymbol && called.classId in schedulers)

private val executors = ClassId(FqName("java.util.concurrent"), Name.identifier("Executors"))

private val schedulerFactories = setOf("newScheduledThreadPool", "newSingleThreadScheduledExecutor")
    .map { CallableId(executors, Name.identifier(it)) }
    .toSet()

private val schedulers = setOf(
    ClassId(FqName("java.util.concurrent"), Name.identifier("ScheduledThreadPoolExecutor")),
    ClassId(FqName("java.util"), Name.identifier("Timer")),
)

/** `Thread.sleep`, or `Timer(..)` for a constructor, whose name is the class's for Java and `<init>` for Kotlin. */
private fun said(symbol: FirCallableSymbol<*>, called: CallableId): String =
    if (symbol is FirConstructorSymbol) {
        "${called.classId?.shortClassName}(..)"
    } else {
        "${called.classId?.shortClassName}.${called.callableName}"
    }
