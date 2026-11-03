# 0272 — Typed policy results

## Problem

A `Policy` learns about a call only from what it throws. An adapter that has already captured an SDK call with
`Either.catch` holds its failure as a value, and handing that value back through `policy { }` makes it a success:
the breaker resets, a retry never runs, an adaptive limit learns a fast answer. Its only way to keep the breaker
honest is to throw the captured failure again and catch it on the far side, which is the control flow the
`Either` was there to remove.

## Not doing

- Changing `Policy.invoke`, `Fail.guarded`, `CircuitBreaker`'s constructor or any step's existing behaviour.
- A typed left other than `Throwable`. Retry schedules take `Throwable`; mapping to an application error
  happens after the policy.
- Letting a custom `Guard` see a typed failure. It wraps the call as today and sees the `Either` as an answer.
- A public `failedIf` on `Limit.withPermit`.

## Shape

```kotlin
fun <A> Policy.guardResult(
    countsAsFailure: (Throwable) -> Boolean = { true },
    block: () -> Either<Throwable, A>,
): Either<Throwable, A>

val charged: Either<Throwable, Charge> =
    payments.guardResult(countsAsFailure = { it !is CardDeclined }) { Either.catch { sdk.charge(request) } }
```

- `Right` is a success to every step.
- A `Left` the selector picks is a failed call: retried by the retry schedule (the schedule sees the same
  throwable), a failure to a breaker whose own `countsAsFailure` also counts it and uncounted to one that does
  not, and a dropped sample to an adaptive `Limit`. When retries end it is answered as the same instance.
- Any other `Left` is the dependency's answer: a success to the breaker (so a half-open trial closes it), not
  retried, a measured answer to a `Limit`, and returned as the same instance.
- What `block` throws, and every refusal or timeout of the policy's own steps, is handled exactly as
  `policy { }` handles it and then answered on the left. The selector reads only lefts `block` returned.
- A fatal throwable, an interrupt, a cancellation and a `fail` on its way out go up, uncounted and never
  retried, even when `block` returned one on the left.
- The selector is asked once per attempt. `surefold.policy.calls` records one outcome per call: `success` for
  a right or an unselected left, as `policy { }` would for a selected left or a throw.
- Internally the runner gains one hook, `(A) -> Throwable?`, that names a returned value as failed; the
  thrown path passes `{ null }`. `Limit.measured` takes the matching `failedIf`.

## Why this shape

Applying the selector to refusals as well (the earlier draft) would let a selector that answers `false` for a
`Rejected` reset the breaker on a bulkhead refusal and skip the retry an attempt timeout should get. Keeping
the policy's own throws on the thrown path means the new entry point adds rules for returned values only, and
one runner serves both. The alternative, a parallel runner over `Either` at every step, duplicates the order
and refund logic each step already gets right. Recommended: one runner with the hook.

## Stack

- [ ] **`claude/spec-0272-guard-result-20261103`** — `Policy.guardResult`, the runner hook, `Limit.measured(failedIf)`,
      `PolicyResultTest`, a compiled example in `docs/core.md` and a cookbook row.
      Done when: tests show selected lefts opening, holding open and failing a half-open breaker without a
      throw; unselected lefts resetting and closing it; the breaker's predicate leaving a selected left
      uncounted; retries, deadlines and attempt timeouts over lefts and throws; rate-limit tokens kept; adaptive
      samples dropped only for selected lefts; fatals, interrupts, cancellation and a foreign `fail` going up;
      the same instance answered; `PolicyTest` unchanged and green.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew :surefold:check :surefold-app:test
```

## Open questions

1. **Should the selector also read what `block` throws?** It would make a throwing block and a capturing one
   equivalent, but a throw already has the breaker's own predicate. Recommended: no; lefts only.
2. **Is an unselected left a breaker success, or uncounted?** Uncounted would leave a half-open breaker open on
   a provider that is answering. Recommended: success.
3. **Name: `guardResult` or `guardedEither`?** An existing caller already compiles against `guardResult`.
   Recommended: keep `guardResult`.
