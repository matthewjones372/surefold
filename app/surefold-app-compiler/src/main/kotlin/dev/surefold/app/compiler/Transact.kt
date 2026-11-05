package dev.surefold.app.compiler

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirVariableAssignmentChecker
import org.jetbrains.kotlin.fir.declarations.FirFunction
import org.jetbrains.kotlin.fir.expressions.FirDesugaredAssignmentValueReferenceExpression
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.expressions.FirQualifiedAccessExpression
import org.jetbrains.kotlin.fir.expressions.FirThisReceiverExpression
import org.jetbrains.kotlin.fir.expressions.FirVariableAssignment
import org.jetbrains.kotlin.fir.references.toResolvedCallableSymbol
import org.jetbrains.kotlin.fir.references.toResolvedVariableSymbol
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirCallableSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirConstructorSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirLocalPropertySymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirPropertySymbol
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Calls inside a step that do something besides change `Shared` values (spec 0210). A step runs again whenever another
 * commit beat it, so I/O, a blocking wait or a fork in its body happens once per run, not once per commit.
 */
internal class TransactChecker : FirFunctionCallChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirFunctionCall) {
        val symbol = expression.calleeReference.toResolvedCallableSymbol() ?: return
        val called = symbol.callableId ?: return
        val said =
            when {
                called == peek -> "${named(symbol, called)} reads outside the step; read it with get()"

                called == tell ->
                    "${named(symbol, called)} runs again each time the step does; once the step commits, " +
                        "tell it with " +
                        "tellAfterCommit(ref, message)"

                called in surefoldEffects || called in printed ->
                    "${named(symbol, called)} runs again each time the step does; once the step commits, run it in " +
                        "afterCommit { }"

                refused(called) ->
                    "${named(symbol, called)} runs again each time the step does, not once per commit; " +
                        "do it before or after transact"

                else -> return
            }
        if (step() != null) report(expression.source, said)
    }
}

/**
 * A write inside a step to a `var` the step did not declare: a local from the enclosing function, a property of the
 * enclosing object, or a top-level one. Each run of the step writes it again, and a run that retries keeps its write.
 */
internal class TransactAssignmentChecker : FirVariableAssignmentChecker(MppCheckerKind.Common) {

    context(context: CheckerContext, reporter: DiagnosticReporter)
    override fun check(expression: FirVariableAssignment) {
        // `x += 1` and `x++` wrap their target in a reference to the access they desugared from.
        val target = (expression.lValue as? FirDesugaredAssignmentValueReferenceExpression)?.expressionRef?.value
        val access = (target ?: expression.lValue) as? FirQualifiedAccessExpression ?: return
        val variable = access.calleeReference.toResolvedVariableSymbol() as? FirPropertySymbol ?: return
        val step = step() ?: return
        val outside =
            when (variable) {
                is FirLocalPropertySymbol -> !variable.declaredIn(step)

                // `this.x` or a bare `x` is the enclosing object's; a property of something else the step was handed is
                // not
                // judged, since the checker cannot see where that object came from.
                else -> access.explicitReceiver.let { it == null || it is FirThisReceiverExpression }
            }
        if (outside) {
            report(
                expression.source,
                "${variable.name} is a var from outside the step, written again each time the step runs; " +
                    "keep it in a Shared",
            )
        }
    }
}

context(context: CheckerContext, reporter: DiagnosticReporter)
private fun report(source: org.jetbrains.kotlin.KtSourceElement?, said: String) =
    reporter.reportOn(source, SurefoldDiagnostics.SUREFOLD_EFFECT_IN_TRANSACT, said, context)

private fun FirBasedSymbol<*>.declaredIn(step: FirBasedSymbol<*>): Boolean {
    val at = source?.startOffset ?: return false
    val within = step.source ?: return false
    return at in within.startOffset until within.endOffset
}

// A transact block, a firstOf branch and a helper written as `fun Transaction.x()` all take Transaction as receiver.
// The outermost is the step: a var declared anywhere inside it is the step's own.
context(context: CheckerContext)
private fun step(): FirBasedSymbol<*>? =
    if (afterCommitted()) {
        null
    } else {
        context.containingDeclarations.firstOrNull {
            (it as? FirCallableSymbol<*>)?.resolvedReceiverType?.classId == transaction
        }
    }

// An afterCommit block runs once the step has committed, so what it does is not the step's. A transact inside one is a
// new step, and is checked again: the block is exempt only when it is deeper than the innermost step around the code.
context(context: CheckerContext)
private fun afterCommitted(): Boolean {
    val path = context.containingElements
    val block = path.indexOfLast {
        (it as? FirFunctionCall)?.calleeReference?.toResolvedCallableSymbol()?.callableId == afterCommit
    }
    val innermost = path.indexOfLast {
        (it as? FirFunction)?.receiverParameter?.typeRef?.coneType?.classId == transaction
    }
    return block > innermost
}

private fun refused(called: CallableId): Boolean {
    val owner = called.classId?.packageFqName ?: called.packageName
    return io.any { owner == it || owner.startsWith(it) } || owner in kotlinIo || called in blocking || called in forks
}

private val surefold = FqName("dev.surefold")

private val transaction = ClassId(surefold, Name.identifier("Transaction"))

private val afterCommit = CallableId(transaction, Name.identifier("afterCommit"))

private val peek = CallableId(ClassId(surefold, Name.identifier("Shared")), Name.identifier("peek"))

private val io = listOf("java.io", "java.net", "java.nio.channels", "java.sql").map(::FqName)

// Kotlin's own files, paths and console, by exact package: kotlin.io.encoding only computes.
private val kotlinIo = setOf(FqName("kotlin.io"), FqName("kotlin.io.path"))

private fun member(pkg: String, cls: String, name: String) =
    CallableId(ClassId(FqName(pkg), Name.identifier(cls)), Name.identifier(name))

private val blocking =
    setOf(
        member("java.lang", "Thread", "sleep"),
        member("java.util.concurrent", "TimeUnit", "sleep"),
        member("java.util.concurrent", "Future", "get"),
        member("java.util.concurrent", "CompletableFuture", "get"),
        member("java.util.concurrent", "CompletableFuture", "join"),
        member("java.util.concurrent.locks", "Lock", "lock"),
        member("java.util.concurrent.locks", "Lock", "lockInterruptibly"),
        member("java.util.concurrent.locks", "ReentrantLock", "lock"),
        member("java.util.concurrent.locks", "ReentrantLock", "lockInterruptibly"),
        member(surefold.asString(), "Deferred", "await"),
        CallableId(FqName("kotlin"), Name.identifier("synchronized")),
    )

private val forks =
    listOf("scope", "parZip", "parMap", "raceN").map { CallableId(surefold, Name.identifier(it)) }.toSet() +
        member(surefold.asString(), "Scope", "async")

// An actor's tell has its own after-commit form, which surefold-actor names tellAfterCommit.
private val tell = member("${surefold.asString()}.actor", "ActorRef", "tell")

// Surefold's own effects: what a step most often wants to happen once it has committed.
private val surefoldEffects =
    listOf("logDebug", "logInfo", "logWarn", "logError", "increment")
        .map { CallableId(surefold, Name.identifier(it)) }
        .toSet() +
        setOf(
            member(surefold.asString(), "Logger", "log"),
            member(surefold.asString(), "Counter", "increment"),
            member(surefold.asString(), "Gauge", "set"),
            member(surefold.asString(), "Histogram", "record"),
            member("${surefold.asString()}.stream", "Hub", "publish"),
        )

// Kotlin's own print and println. A PrintStream's are refused with the rest of java.io.
private val printed = listOf("print", "println").map { CallableId(FqName("kotlin.io"), Name.identifier(it)) }.toSet()

/** `Thread.sleep`, `Socket(..)` for a constructor, or `parMap` for a top-level function. */
private fun named(symbol: FirCallableSymbol<*>, called: CallableId): String {
    val owner = called.classId?.shortClassName
    return when {
        symbol is FirConstructorSymbol -> "$owner(..)"
        owner == null -> called.callableName.asString()
        else -> "$owner.${called.callableName}"
    }
}
