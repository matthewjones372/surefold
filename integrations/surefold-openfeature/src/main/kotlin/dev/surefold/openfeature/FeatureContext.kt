package dev.surefold.openfeature

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.ImmutableContext
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.TransactionContextPropagator
import dev.openfeature.sdk.Value
import dev.openfeature.sdk.exceptions.TypeMismatchError
import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal

/**
 * Who a flag is evaluated for, bound where the request came in and read wherever a flag is: a fork
 * inherits it, and [FeatureContextCarrier] carries it across a message. Empty where nothing bound it.
 */
val featureContext: SurefoldLocal<EvaluationContext> = surefoldLocal { ImmutableContext.EMPTY }

/**
 * The SDK's transaction context, kept in [featureContext]. The SDK's own propagator is a `ThreadLocal`,
 * which every surefold combinator leaves behind: a flag read in a `parZip` branch would answer for nobody.
 */
class SurefoldTransactionContextPropagator : TransactionContextPropagator {

    override fun getTransactionContext(): EvaluationContext = featureContext.get()

    /** Binds [evaluationContext] for the rest of this thread's bindings, as the `ThreadLocal` one does. */
    override fun setTransactionContext(evaluationContext: EvaluationContext) {
        featureContext.attach(evaluationContext)
    }
}

/**
 * Runs [block] with [targetingKey] and [attributes] merged over the context already bound, so a nested
 * call adds to the one around it. An attribute is anything OpenFeature's `Value` holds.
 */
fun <A> evaluatedFor(targetingKey: String, vararg attributes: Pair<String, Any>, block: () -> A): A {
    val added = ImmutableContext(targetingKey, attributes.associate { (name, value) -> name to valueOf(name, value) })
    return featureContext.locally(featureContext.get().merge(added), block)
}

private fun valueOf(name: String, value: Any): Value = try {
    Value.objectToValue(value)
} catch (unsupported: TypeMismatchError) {
    val type = value::class.qualifiedName
    throw IllegalArgumentException("attribute $name is a $type, which a Value cannot hold", unsupported)
}

/**
 * Keeps the SDK's transaction context in [featureContext] and adds [SurefoldFeatureHook], for every client in
 * this JVM. Call it in `main`; a second call adds nothing.
 */
fun OpenFeatureAPI.useSurefold() {
    transactionContextPropagator = SurefoldTransactionContextPropagator()
    if (hooks.none { it === SurefoldFeatureHook }) addHooks(SurefoldFeatureHook)
}
