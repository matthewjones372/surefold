# 0272 — typed policy results

Owner-authorized prerequisite for removing Doorlist's exception-based payment control flow, 1 November 2026.

## Problem

Policy counts thrown failures while a typed Fail unwinds uncounted. A provider adapter cannot retain Either
through the policy and preserve breaker/retry semantics without deliberately throwing a captured failure.

## Not doing

Changing legacy Policy.invoke/Fail.guarded, provider policy, new dependencies or application error types.

## Shape

`Policy.guardResult(countsAsFailure: (Throwable) -> Boolean = { true }, block: () -> Either<Throwable, A>)`
returns Either<Throwable,A>. Right is successful. Selected Left is a policy failure; unselected Left is a
successful provider answer, so it resets the breaker's consecutive failure count and is not retried.
The same selected errors are inputs to existing Throwable retry schedules. Existing policy ordering, guards,
budgets, deadlines, timeouts, half-open admission and metrics remain effective. Guard refusal becomes Left.
Fatal errors, interruption, cancellation and foreign Fail unwinding propagate and are never retried/counted,
even when supplied as Left. An unmatched provider failure remains the identical object on Left.
Respect the breaker's existing configured countsAsFailure predicate in addition to the result selector.

## Why this shape

It consumes captured SDK outcomes directly without introducing a throw to trip a breaker. Restricting the left
to Throwable preserves the existing retry schedule contract; application domain mapping happens afterwards.

## Stack

- [ ] **codex/catch-removal-draining** — additive result entry point and independent semantic tests.
  Done when typed failures open the breaker, known refusals reset it, retries/deadlines/guards are preserved,
  and legacy behavior checks pass. This is separate from the already verified drain primitive commit.

## Acceptance

Test first, then focused core policy tests, core check/coverage/lint, compiler and guide checks as applicable.
Consumer source snapshot stays immutable and is repinned only after verification. No remote release/deployment.

## Open questions

None: actual provider behavior and source show the gap; the existing API remains unchanged.
