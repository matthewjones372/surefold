# 0269 — A quiet one-shot handoff decision

Approved contract; source is on main via PR973/main46213a81. No stable release is recorded here.

## Problem

An owner needs a final decision after every fallible preparation/release has ended. Promise completion can
commit before fallible instrumentation, and its transaction admission can be interrupted. A final resource
return cannot safely add either ambiguity. The new boundary records only a decision; it does not wake anyone.

## Not doing

No Promise/Shared/Resource change, waiter/wakeup/join, latch, timeout, cancellation engine, transaction
integration, resource policy, executor, scheduler, module, cryptography or dependency. No physical thread join.

## Shape

Public API on main in core, package io.github.matthewjones372.surefold:
```kotlin
class HandoffDecision<A : Any> {
    fun tryComplete(value: A): Boolean
    fun peek(): A?
}
```
Construct before acquiring resources; private state and final methods. Null means pending only. Java null
input is rejected before mutation. One completion wins by reference CAS and retains that exact reference;
later attempts return false, without user equality/hashCode. Payloads are not copied or frozen; their later
mutation is caller-synchronised. Completion publishes preceding writes to any peek observing that result.
Linearization is the successful CAS. A reader may observe its result before the completing invocation returns.
For valid prebuilt input, completion and peek run no user callback, metric/tag/tracing, retry observer, TurnSeam,
transaction, interrupt check, wait, lock or wakeup. They preserve an existing interrupt flag. They perform no
library allocation or other fallible library action after commitment. No reset, implicit abort or failure state.
Use inside a retryable transaction is unsupported; the decision never joins or rolls back with Shared writes.
No VM-fatal/forced-stop/bounded-scheduling guarantee. Completed means only this decision, not caller termination.
Claims concern library-owned ordinary paths on the checked supported JDK, not arbitrary bytecode agents or VM rewrites;
compiled dispatch and enabled instrumentation must be inspected before source approval. Never call this infallible.
Only this NEW primitive lacks instrumentation; existing loader/ledger/material/cleanup observability stays active.

## Why this shape

A private AtomicReference allocated at construction, direct compareAndSet(null,value), and get suffice for the
ordinary path; source review must confirm supported-JDK dispatch and no added work after CAS. No Word/MCAS/Solo.
The earlier atomic-plus-CountDownLatch proposal is WITHDRAWN: actual Temurin25.0.4.1 unpark of a virtual waiter
can schedule and throw rejection after commitment, and its pinned path can acquire an internal monitor.
No awaitCompletion method or observer waiting algorithm is selected. Future accepted-caller join remains OPEN;
SingleFlight.closeAndJoin covers its workers only, and a peek does not manufacture a caller-boundary join.

## Stack

- [x] **codex/handoff-decision-contract** — PR969/main6e5113d5: numbered spec and complete0265 ownership amendment.
- [x] **codex/handoff-decision** — PR973/main46213a81: class/tests, core-guide paragraph/cookbook row; no existing API edits.
      Main/docs estimate30–50, stop/review before190. Tests separately counted. Required full checks unchanged.

## Acceptance

Record missing-API compile separately. Establish ordinary completion, then meaningful quiet-publication REDs
against an observable candidate, without changing old Promise/Shared. Test first winner/exact identity, competing
completions, pending/repeated peek, pre-interrupted completion/read preserving the flag, payload visibility,
no user equality/hashCode and no Metrics/tags/retry/TurnSeam callbacks on the decision's own call path.
Use existing owned scopes/Promises for concurrency gates; observer test loops are not a new public waiting API.
Source proves absence of scheduler/transaction paths; tests do not prove all schedules or JVM behavior.
Typed detektMain/Test, spotlessApply, normal full build and required PR/main CI/coverage/dependencies remain.

## Open questions

Root selected this two-method quiet marker, with no observer waiting contract. Alternative considered: leave B2
blocked. Accepting new uninstrumented state does not suppress failures from any existing primitive.
A separate reviewed concrete accepted-caller join contract is REQUIRED before the later joined-release entry;
no latch, polling, wakeup, timeout or physical join design is authorised here. Number0269 is confirmed at the
spec-only publication; later source remains separately commissioned.
