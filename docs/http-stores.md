# Idempotent requests and rate limits

Two stores for an HTTP service with several instances: `surefold-idempotency`, so a client that retries a `POST` it
never saw answered is charged once, and `surefold-ratelimit`, so a client or a tenant draws on one budget whichever
instance it reaches. Both keep their state in the Postgres the service already runs.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold-idempotency:0.17.0")
    implementation("io.github.matthewjones372:surefold-ratelimit:0.17.0")
}
```

These stores hold the state; reading the `Idempotency-Key` header, fingerprinting the request, and answering 409, 422
or 429 belong to the HTTP layer. A service calls them from its handlers, as below. Surefold ships no adapter to any
HTTP framework: a framework built on Surefold, such as Pelican, puts its own interfaces over these stores.

<!-- http-stores -->
```kotlin
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
```

## Idempotency keys

`reserve(key, fingerprint, ttl)` answers one of four things:

| `Reservation` | What to do |
|---|---|
| `Reserved(token)` | run the handler, then `complete(key, token, response)`; if it fails, `release(key, token)` so a retry can run |
| `Replay(response)` | answer with the first run's stored status, headers and body |
| `InProgress` | another instance is running this request now: 409 |
| `Mismatch` | the key was used for a different request: 422 |

Reserving is one `insert … on conflict do update … where` that only an expired key, or a reservation whose lease ran
out, lets through, so of any number of instances reserving one key at once exactly one is `Reserved`. A reservation
holds a lease, a minute by default: an instance that dies mid-request leaves a key the next retry reserves once the
lease passes, and the dead instance's late `complete` changes nothing, because its token no longer holds the key.

A key lives for its `ttl`, 24 hours by default. Prune expired keys with a `surefold-jobs` worker:

```kotlin
jobWorker(
    JobHandlers.of(IdempotencyPrune.handledBy(store)),
    cron = listOf(IdempotencyPrune.every(Cron.parse("0 * * * *", ZoneOffset.UTC))),
)
```

The table ships as `surefold/idempotency/postgres.sql`, for the service's changelog to include, or for Flyway as
`flyway(SurefoldSchema.IDEMPOTENCY, …)` ([Flyway](flyway.md)).

## Rate limits

`take(key, TokenBucket(rate, per, burst), cost)` answers `Allowed(remaining)` or `Denied(retryAfter)`, the time until
the tokens asked for are there, which is what a `Retry-After` header says. A bucket is full the first time a key is
seen. `PostgresRateLimits` refills and draws in one statement on the key's row, so every instance shares one bucket;
`InMemoryRateLimits` is for a single instance or a test. The table ships as `surefold/ratelimit/postgres.sql`, or for Flyway as `flyway(SurefoldSchema.RATELIMIT, …)`.
