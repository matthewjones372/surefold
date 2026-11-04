# 0303 — A retry budget every call shares

## Problem

A `Policy`'s retry is decided one call at a time. When a dependency goes down, every in-flight call retries it
up to its schedule's limit, so a service with `recurs(3)` sends up to four times its normal load to the thing
that is already failing. The breaker stops this only once it opens, and until then each failed call has already
made all its retries. A team that wants retries capped across the whole service writes its own `AtomicInteger`
inside a custom `Guard`, which cannot see whether the next attempt is a retry or a first try.

## Not doing

- **A budget on `Schedule.retry` or `Fail<E>.retry`.** Only `Policy` gets one here; see the open questions.
- **A budget measured over time** (retries per second, a sliding window). The bucket below needs no clock.
- **Sharing a budget across processes.** It is in memory, like `CircuitBreaker`.
- **Changing what a retry does when the budget has tokens.** The schedule decides exactly as today.

## Shape

In `surefold`, beside `CircuitBreaker.kt`, with no new dependency:

```kotlin
import dev.surefold.RetryBudget
import dev.surefold.Schedule
import dev.surefold.policy
import kotlin.time.Duration.Companion.milliseconds

// Roughly one retry for every ten successes, once the first 100 tokens are spent.
val payments = RetryBudget("payments", capacity = 100, retryCost = 10, successRefill = 1)

val charge = policy("payments.charge") {
    retry(Schedule.exponential<Throwable>(50.milliseconds) zipLeft Schedule.recurs(3), budget = payments)
}
```

- **A token bucket**, full at `capacity`. A retry the schedule asked for takes `retryCost` tokens before it
  sleeps. A call that succeeds puts back `successRefill`, up to `capacity`. A first attempt costs nothing.
- **An empty bucket ends the retry.** The call answers with the failure it has, rethrown as the schedule's
  `Done` would rethrow it. It is not a `Rejected`, because the attempt did fail and was not refused.
- **One budget serves many policies.** Every policy that names `payments` draws on the same bucket, as every
  caller of one `Limit` does (spec 0171).
- **What counts as a success** is what `surefold.policy.calls` already records as `success`: a `fail(e)`
  passing through and a cancellation refill nothing. Under `guardResult` (spec 0272) an unselected left counts
  as a success.
- **Metrics:** a gauge `surefold.retry.budget.tokens` and a counter `surefold.retry.budget.exhausted`, both
  tagged `budget`.
- `Policy.Step.Retry` gains `val budget: RetryBudget? = null`. Without one, a retry behaves as it does today.

## Why this shape

A bucket where retries cost and successes refill caps retry amplification at about `successRefill / retryCost`
of the traffic once the bucket has drained, with one compare-and-set per call and no clock. A budget fed by
`TestClock` would make tests depend on time for no gain. **Alternative:** a ratio over a time window (Finagle's
`RetryBudget`). It reads more naturally ("10% extra") but needs a clock and a window to tune. Recommended: the
bucket. **Alternative:** a step of its own in the policy order. It would still have to sit with the retry, since
only the retry knows which attempt is a retry. Recommended: a parameter on `retry`.

## Stack

- [ ] **`spec-0303-bucket`** — `RetryBudget`: take, refill, cap, its gauge and counter, `RetryBudgetTest`.
      Done when: two threads draining one budget concurrently never take it below zero or refill it past
      `capacity`.
- [ ] **`spec-0303-policy`** — `retry(schedule, budget)`, `Step.Retry.budget`, the refill on success, a compiled
      example under "Policies" in `docs/core.md`.
      Done when: with a budget of 20 and a cost of 10, ten calls failing in a row make two retries between them,
      not thirty, and a success afterwards refills the bucket.

## Acceptance

```bash
./gradlew :surefold:test --tests '*RetryBudget*' --tests '*Policy*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `Fail<E>.retry` and `Schedule.retry` take a budget too?** It is a small change to each loop, but
   only a policy sees successes on every call. Recommended: no, `Policy` only, until someone needs it elsewhere.
2. **Defaults:** require all three numbers, or default to `capacity = 500, retryCost = 5, successRefill = 1`
   (Ox's numbers, about 20%)? Recommended: require `capacity` and default the other two to 10 and 1 (about 10%).
3. **Should a retry the deadline stops (`within`) still take tokens?** Recommended: no. Tokens are taken only
   when a retry is actually about to sleep.
