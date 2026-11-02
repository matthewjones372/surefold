# 0273 — Nontransactional ownership boundaries

Spec-only promotion of independently reviewed consumer proposal26595fcbd759975929ce1f050bbbca971f46b879.
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
