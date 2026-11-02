package io.github.matthewjones372.surefold.idempotency

import dev.surefold.ByteChunk
import java.util.UUID
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours

/** A key a client sent with a request, within a [scope] the service chooses: a tenant, or a client, say. */
data class IdempotencyKey(val scope: String, val key: String) {
    init {
        require(scope.isNotBlank() && key.isNotBlank()) { "An idempotency key needs a scope and a key." }
        require(key.length <= MOST_KEY) { "An idempotency key is at most $MOST_KEY characters, not ${key.length}." }
    }

    private companion object {
        const val MOST_KEY = 255
    }
}

/** A response kept to answer a repeat of its request with: status, headers in order, and body. */
data class StoredResponse(val status: Int, val headers: List<Pair<String, String>>, val body: ByteChunk) {
    override fun toString(): String = "StoredResponse($status, $headers, ${body.size} bytes)"
}

/** What reserving a key found. */
sealed interface Reservation {
    /** Run the handler, then [IdempotencyStore.complete] with [token], or [IdempotencyStore.release] if it failed. */
    data class Reserved(val token: UUID) : Reservation

    /** The request ran before: answer with [response] again. */
    data class Replay(val response: StoredResponse) : Reservation

    /** Another instance is running this request now. */
    data object InProgress : Reservation

    /** The key was used for a different request. */
    data object Mismatch : Reservation
}

/**
 * Where idempotent requests keep their keys and answers, shared by every instance. [reserve] is atomic: of any number
 * of instances reserving one key at once, exactly one is [Reservation.Reserved].
 */
interface IdempotencyStore {
    /** Reserves [key] for a request whose content hashes to [fingerprint], keeping its answer for [ttl]. */
    fun reserve(key: IdempotencyKey, fingerprint: String, ttl: Duration = 24.hours): Reservation

    /** Keeps [response] as [key]'s answer. False when [token]'s reservation was taken over, or released. */
    fun complete(key: IdempotencyKey, token: UUID, response: StoredResponse): Boolean

    /** Gives [key] up, so a retry of its request can run. False when [token]'s reservation was not still held. */
    fun release(key: IdempotencyKey, token: UUID): Boolean
}
