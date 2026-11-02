package dev.surefold.bank

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive

/** The bank's JSON: a field a route does not know is ignored, so a page may send more than the route reads. */
internal val json = Json { ignoreUnknownKeys = true }

/**
 * [body] as an [A], or null when it is not JSON or not that shape. `SerializationException` is an
 * `IllegalArgumentException`, and catching the wider one keeps any other refusal from the decoder a 400.
 */
internal inline fun <reified A> decoded(body: String): A? = try {
    json.decodeFromString<A>(body)
} catch (_: IllegalArgumentException) {
    null
}

// A request body's fields have defaults and its numbers and flags are read as written, so a field that is missing,
// fractional or a string reaches its route, and the route says what is wrong with it rather than "the body is not".

@Serializable
internal data class OpenBody(val id: String = "", val amount: JsonPrimitive? = null)

@Serializable
internal data class TransferBody(val from: String = "", val to: String = "", val amount: JsonPrimitive? = null)

@Serializable
internal data class LoadBody(val on: JsonPrimitive? = null)

@Serializable
internal data class CrashBody(val node: String = "")

/** A field as a whole number, whether it was written bare or as a string; null for `1.5`, `"x"` or no field. */
internal fun JsonPrimitive?.whole(): Long? = this?.content?.toLongOrNull()

/** A field as `true` or `false`, whether it was written bare or as a string. */
internal fun JsonPrimitive?.flag(): Boolean? = this?.content?.toBooleanStrictOrNull()

@Serializable
internal data class ErrorBody(val error: String)

@Serializable
internal data class BalanceBody(val id: String, val balance: Long)

@Serializable
internal data class MovementBody(val transfer: String, val amount: Long)

@Serializable
internal data class AccountBody(val id: String, val balance: Long, val movements: List<MovementBody>)

@Serializable
internal data class AcceptedBody(val id: String)

@Serializable
internal data class LoadedBody(val load: Boolean)

@Serializable
internal data class CrashedBody(val crashed: String)

@Serializable
internal data class StatusBody(val id: String, val status: String)
