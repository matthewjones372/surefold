package dev.surefold.openfeature

import dev.openfeature.sdk.EvaluationContext
import dev.openfeature.sdk.FeatureProvider
import dev.openfeature.sdk.Metadata
import dev.openfeature.sdk.ProviderEvaluation

/**
 * A provider that answers with what it was asked for: a string flag with the targeting key, an integer
 * flag with the attribute of that name. A flag read with no context bound answers with its default.
 */
internal object Echo : FeatureProvider {

    override fun getMetadata() = Metadata { "echo" }

    override fun getStringEvaluation(key: String, defaultValue: String, ctx: EvaluationContext) =
        answer(ctx.targetingKey?.takeIf { it.isNotEmpty() } ?: defaultValue)

    override fun getIntegerEvaluation(key: String, defaultValue: Int, ctx: EvaluationContext) =
        answer(ctx.getValue(key)?.takeIf { it.asObject() is Int }?.asInteger() ?: defaultValue)

    override fun getBooleanEvaluation(key: String, defaultValue: Boolean, ctx: EvaluationContext) =
        answer(ctx.getValue(key)?.asBoolean() ?: defaultValue)

    override fun getDoubleEvaluation(key: String, defaultValue: Double, ctx: EvaluationContext) =
        answer(ctx.getValue(key)?.takeIf { it.asObject() is Double }?.asDouble() ?: defaultValue)

    override fun getObjectEvaluation(
        key: String,
        defaultValue: dev.openfeature.sdk.Value,
        ctx: EvaluationContext,
    ) = answer(defaultValue)

    private fun <A> answer(value: A): ProviderEvaluation<A> = ProviderEvaluation.builder<A>().value(value).build()
}
