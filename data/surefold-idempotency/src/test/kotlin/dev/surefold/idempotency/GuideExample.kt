package dev.surefold.idempotency

// The example docs/http-stores.md shows, from the imports down: HttpStoresGuideSuite holds the page to this file.
import dev.surefold.ByteChunk
import dev.surefold.ratelimit.Decision
import dev.surefold.ratelimit.RateLimits
import dev.surefold.ratelimit.TokenBucket
import kotlin.time.Duration.Companion.seconds

/** What an endpoint answers: a status and a body, as an HTTP layer would send them. */
data class Answer(val status: Int, val body: String)

class Payments(private val keys: IdempotencyStore, private val limits: RateLimits) {
    private val perClient = TokenBucket(10, per = 1.seconds, burst = 20)

    /** Takes a payment once per idempotency key, however many times the client retries the request. */
    fun pay(tenant: String, client: String, key: String, request: String, charge: () -> String): Answer {
        val decision = limits.take("pay:$tenant:$client", perClient)
        if (decision is Decision.Denied) return Answer(429, "retry after ${decision.retryAfter}")
        val idempotency = IdempotencyKey(scope = tenant, key = key)
        return when (val reservation = keys.reserve(idempotency, fingerprint = request.hashCode().toString())) {
            is Reservation.Reserved -> {
                val receipt = try {
                    charge()
                } catch (failed: IllegalStateException) {
                    keys.release(idempotency, reservation.token)
                    throw failed
                }
                keys.complete(idempotency, reservation.token, StoredResponse(201, emptyList(), ByteChunk.from(receipt)))
                Answer(201, receipt)
            }

            is Reservation.Replay -> Answer(reservation.response.status, reservation.response.body.decodeToString())

            Reservation.InProgress -> Answer(409, "this payment is being taken")

            Reservation.Mismatch -> Answer(422, "this key was used for another payment")
        }
    }
}
