package io.github.matthewjones372.surefold.openfeature

import dev.openfeature.sdk.Client
import dev.openfeature.sdk.ErrorCode
import dev.openfeature.sdk.FlagEvaluationDetails
import dev.openfeature.sdk.OpenFeatureAPI
import dev.openfeature.sdk.Value
import dev.surefold.SurefoldLocal
import dev.surefold.surefoldLocal
import java.time.Instant
import kotlin.time.Duration

/**
 * The client a [Flag] is read through: the default domain's, unless a test or a service with several
 * providers binds another with `locally`. A fork reads the one its opener bound.
 */
val featureClient: SurefoldLocal<Client> = surefoldLocal { OpenFeatureAPI.getInstance().client }

/**
 * A flag declared once: its key, its type and the default it answers when it cannot be evaluated, which
 * 0180's hook then reports. Read with [value] or [details] against [featureClient] and the context
 * `evaluatedFor` bound, so a call site passes nothing in.
 */
class Flag<A : Any> private constructor(
    val key: String,
    val default: A,
    private val read: Client.(String, A) -> FlagEvaluationDetails<A>,
    private val check: (Value) -> String?,
) {
    /** The flag's value for whoever is bound, or [default] if it cannot be evaluated. */
    fun value(): A = details().value ?: default

    /** The evaluation behind [value]: its variant, its reason, and the error code if it fell back. */
    fun details(): FlagEvaluationDetails<A> = featureClient.get().read(key, default)

    /**
     * Why [variant] would not read as this flag's type, or null when it would. It decodes as [value] does, so a
     * provider whose variants are known before the first read can be checked against the flags declared on it.
     */
    fun rejects(variant: Value): String? = check(variant)

    companion object {
        fun boolean(key: String, default: Boolean): Flag<Boolean> =
            Flag(key, default, { k, d -> getBooleanDetails(k, d) }) { it.unless(it.isBoolean, "Boolean") }

        fun string(key: String, default: String): Flag<String> =
            Flag(key, default, { k, d -> getStringDetails(k, d) }) { it.unless(it.isString, "String") }

        /** An integer flag. [rejects] passes a whole number written `5.0`, but not `5.5`, which flagd reads as 5. */
        fun int(key: String, default: Int): Flag<Int> =
            Flag(key, default, { k, d -> getIntegerDetails(k, d) }) {
                whole(it, "Int", Int.MIN_VALUE.toLong()..Int.MAX_VALUE)
            }

        fun double(key: String, default: Double): Flag<Double> =
            Flag(key, default, { k, d -> getDoubleDetails(k, d) }) { it.unless(it.isNumber, "Double") }

        /** An integer flag read as a [Long], through the SDK's own long evaluation. */
        fun long(key: String, default: Long): Flag<Long> =
            Flag(key, default, { k, d -> getLongDetails(k, d) }) {
                whole(it, "Long", -MAX_SAFE_INTEGER..MAX_SAFE_INTEGER)
            }

        /** A string flag read as a [Duration]: `"5s"`, `"1m 30s"` or ISO-8601's `"PT5S"`. */
        fun duration(key: String, default: Duration): Flag<Duration> =
            fromString(key, default, "Duration", Duration::parse)

        /** A string flag read as an ISO-8601 [Instant]. */
        fun instant(key: String, default: Instant): Flag<Instant> = fromString(key, default, "Instant", Instant::parse)

        /** A string flag read as one of [E]'s constants, by exact name: a wrong case is a typo, not a match. */
        inline fun <reified E : Enum<E>> enum(key: String, default: E): Flag<E> =
            fromString(key, default, E::class.simpleName ?: "enum") { name -> enumValueOf<E>(name) }

        /** An object flag read through [decode], which the caller writes against the SDK's [Value]. */
        fun <A : Any> of(key: String, default: A, decode: (Value) -> A): Flag<A> =
            Flag(key, default, { k, d -> decoded(getObjectDetails(k, Value()), d, decode) }) {
                failed(it, null, decode)
            }

        @PublishedApi
        internal fun <A : Any> fromString(key: String, default: A, type: String, decode: (String) -> A): Flag<A> =
            Flag(key, default, { k, d -> decoded(getStringDetails(k, d.toString()), d, decode) }) {
                it.unless(it.isString, "String") ?: failed(it, type) { variant -> decode(variant.asString()) }
            }

        /** The SDK's own bound on a long read: past it, a double no longer holds every whole number. */
        private const val MAX_SAFE_INTEGER = (1L shl 53) - 1

        private fun Value.unless(holds: Boolean, type: String): String? =
            if (holds) null else "is ${kind()}, not ${type.withArticle()}"

        private fun whole(variant: Value, type: String, range: LongRange): String? {
            val number = variant.asObject() as? Number ?: return variant.unless(false, type)
            val n = number.toDouble()
            return when {
                number is Double && !(n.isFinite() && n % 1.0 == 0.0) -> "$number is not a whole number"
                n < range.first || n > range.last -> "$number is outside ${type.withArticle()}'s range"
                else -> null
            }
        }

        @Suppress("TooGenericExceptionCaught") // as in decoded: whatever the caller's decode throws is a bad value
        private fun <A> failed(variant: Value, type: String?, decode: (Value) -> A): String? =
            try {
                decode(variant)
                null
            } catch (thrown: Exception) {
                val shown = if (variant.isString) "\"${variant.asString()}\"" else "${variant.asObject()}"
                val why = thrown.message ?: thrown::class.simpleName
                "$shown did not decode${type?.let { " as ${it.withArticle()}" }.orEmpty()}: $why"
            }

        private fun String.withArticle(): String = if (first() in "AEIOU") "an $this" else "a $this"

        private fun Value.kind(): String = when {
            isNull -> "null"
            isBoolean -> "a boolean"
            isString -> "a string"
            isNumber -> "a number"
            isStructure -> "a structure"
            isList -> "a list"
            isInstant -> "an instant"
            else -> "a ${asObject()::class.simpleName}"
        }

        /**
         * [raw] decoded, or [default] when the SDK already fell back, which its hook has reported. A decode
         * that throws answers [default] with `PARSE_ERROR`, reported here since the hook has already run.
         */
        @Suppress("TooGenericExceptionCaught") // a decode is the caller's function; whatever it throws is a bad value
        private fun <R, A : Any> decoded(raw: FlagEvaluationDetails<R>, default: A, decode: (R) -> A) =
            if (raw.errorCode != null) {
                raw.carrying(default, raw.errorCode, raw.errorMessage)
            } else {
                try {
                    raw.carrying(decode(raw.value), null, null)
                } catch (thrown: Exception) {
                    val message = "${raw.value} did not decode: ${thrown.message ?: thrown::class.simpleName}"
                    fellBack(raw.flagKey, ErrorCode.PARSE_ERROR, message)
                    raw.carrying(default, ErrorCode.PARSE_ERROR, message)
                }
            }

        private fun <R, A> FlagEvaluationDetails<R>.carrying(value: A, code: ErrorCode?, message: String?) =
            FlagEvaluationDetails.builder<A>()
                .flagKey(flagKey)
                .value(value)
                .variant(variant)
                .reason(if (code == ErrorCode.PARSE_ERROR) "ERROR" else reason)
                .errorCode(code)
                .errorMessage(message)
                .flagMetadata(flagMetadata)
                .build()
    }
}
