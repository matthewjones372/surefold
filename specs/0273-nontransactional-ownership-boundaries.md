# 0273 — Nontransactional ownership boundaries

Spec-only promotion of independently reviewed Doorlist proposal26595fcbd759975929ce1f050bbbca971f46b879.
Checked main91040b935a7ae84b7be9c6c89e5e268cb3b48899 and empty open-PR listing, 2 November2026; no source exists.

## Problem

Shared.modify/update join ambient transactions: ownership admission/fencing may repeat or commit after external
work. Spec0265 needs to refuse that context before any ownership effect.

## Not doing

No detachment/commit, wrapper, introspection/wake/wait API, scheduler, dependency, activation or existing transaction/observer/binding change.

## Shape

Proposed additive core API in package io.github.matthewjones372.surefold, existing Transact.kt:
```kotlin
fun checkNotInTransaction(): Unit
```
If the private current transaction binding is present, throw IllegalStateException("the operation cannot run inside a transaction"); otherwise return Unit.
Call BEFORE admission/fencing. Nested/forced-solo transactions are refused too; outer transaction behavior stays.
The guard adds no ownership action, transaction write/detachment, instrumentation or interrupt check/clear.

## Why this shape

Refusal preserves caller semantics; detached effects cannot roll back when the ambient transaction retries.
A transaction handle/general wrapper exceeds this need. The proposed signature is not shipped.

## Stack

- [ ] **codex/key-callers-precode-20261102** — this guard and0265 R4 amendment; exact-spec review/spec-only main.
- [ ] **codex/transaction-boundary-guard** — Transact.kt guard, new TransactionBoundaryTest.kt, compiled core-guide
      example/cookbook row; target<60 main/docs lines. Done after separate exact source-plan review and required checks/main.

## Acceptance

`./gradlew :surefold:check :surefold-app:check`, then `./gradlew spotlessApply && ./gradlew build`.
Distinguish missing-API compile from meaningful RED: outside return, nested/forced-solo refusal before ownership/
body/fence, unchanged interruption; omitting guard must fail. Owned Surefold gates, no sleeps/hooks/custom concurrency.
Preserve transaction tests, typed detekt, Kover90%, dependencies, formatting and exact PR/main checks; no results claimed.

## Open questions

Resolved by independent Top review of26595fcbd: the one refusal signature/error above, no detachment. Exact source
plan/example markers remain a separate committed review gate.0273 rechecked on main/open PRs; known0271/0272 labels
untouched, unpublished trees not exhaustively inspected. Spec-only main precedes source; no inferred implementation approval.

## Exact source plan (separate review gate; no source authorised here)

- Gate: precode PR980/Doorlist PR484 approved main/checks and independent committed-plan review before source; no R4 callers/tickets, introspection or activation.
- In `core/surefold/src/main/kotlin/io/github/matthewjones372/surefold/Transact.kt`, immediately before `transact`'s
  existing KDoc, add one-line KDoc `/** Refuses retryable steps before ownership effects can escape them. */`
  and `fun checkNotInTransaction(): Unit { check(running.get() == null) { "the operation cannot run inside a transaction" } }`.
  Read only private `running: ThreadLocal<Transaction?>`; no new state/transactions/metrics/dependencies, binding mutation, catch, interrupt check/clear or transaction/observer changes.
- New `core/surefold/src/test/kotlin/io/github/matthewjones372/surefold/TransactionBoundaryTest.kt`: JUnit5 sentence
  tests, `shouldThrow<IllegalStateException>`/`shouldBe`, exact message/identity; missing-API compile is separate:
  temporary no-op body must compile, then yield meaningful RED before fix.
  For ordinary `transact`, nested `transact` and `transact(soloAfter = 0)`, guard before preallocated
  `HandoffDecision<Unit>.tryComplete(Unit)` admission/body/fence probes; assert refusal and all `peek() == null`.
  Outside a transaction assert Unit and probes complete; afterCommit assert guard succeeds and sees committed Shared.
  Catch refusal with shouldThrow inside outer step, then read/write its Shared and commit; separately throw a
  preallocated primary after refusal: identity, rollback and later outside/transaction success must hold.
  Assert flag unchanged outside and inside an already-entered ordinary step, restoring prior status in test finally;
  no new catch, sleeps, hooks or custom concurrency. Omitting/no-op guard must fail refusal and probe assertions.
- `docs/core.md`, after Values changed together's bullet list: `### Nontransactional ownership boundaries`, unreleased
  guidance and `<!-- core-transaction-boundary -->` standalone guard-before-callback example, explicit import, existing core
  dependency guidance. `docs/cookbook.md`: one Need/Use/Module row linking heading; CoreGuideTest marker-presence assertion.
- Forecast production/docs <60 changed lines (tests separate); stop for scope review if exceeded. Focused RED/GREEN:
  `:surefold:test --tests '*TransactionBoundaryTest'`; preserve TransactTest/AfterCommitTest/SoloTest/TransactMetricsTest.
  Run `:surefold:check :surefold-app:check`, affected tasks from `.github/affected-tasks.sh origin/main`, `:surefold-app-pekko:test --tests '*CoreGuideTest' --tests '*CookbookTest'`,
  then `./gradlew spotlessApply && ./gradlew build`; retain typed detekt, Kover90%, dependency and exact PR/main gates.
