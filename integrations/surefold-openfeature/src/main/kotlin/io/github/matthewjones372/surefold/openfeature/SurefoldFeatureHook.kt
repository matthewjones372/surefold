package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.Hook
import dev.openfeature.sdk.HookContext
import dev.surefold.counter
import dev.surefold.increment
import dev.surefold.logWarn

/**
 * Every evaluation counted under `openfeature.evaluations`, by flag, variant and reason, and every one
 * that fell back to its default warned about and counted under `openfeature.errors`, by flag and error
 * code. A flag never fails a request, so without this a provider that is down looks like one that said no.
 *
 * Written through surefold's `logger` and `metrics` locals, so whatever backend the service bound gets it.
 */
object SurefoldFeatureHook : Hook<Any> {

    override fun finallyAfter(ctx: HookContext<Any>, details: FlagEvaluationDetails<Any>, hints: Map<String, Any>) {
        val flag = details.flagKey
        counter(
            "openfeature.evaluations",
            "flag" to flag,
            "variant" to details.variant.orEmpty(),
            "reason" to details.reason.orEmpty(),
        ).increment()
        val code = details.errorCode ?: return
        fellBack(flag, code, details.errorMessage)
    }
}

/** A flag that answered its default: one warning naming it and [code], and one `openfeature.errors` reading. */
internal fun fellBack(flag: String, code: ErrorCode, message: String?) {
    logWarn("flag $flag answered with its default: $code ${message.orEmpty()}".trimEnd())
    counter("openfeature.errors", "flag" to flag, "code" to code.name).increment()
}
