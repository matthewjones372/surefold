# 0271 — interruption-preserving drains

Owner-authorized prerequisite for a consumer's raw-catch removal, 1 November 2026; re-landed on main 3 November 2026.

## Problem

Owners must finish shutdown waits despite interruption and return the interrupt to their caller. A private consumer
repeats this mechanism in its own catch adapter because Scope's internal joining protocol is private.

## Not doing

New concurrency, executors, cancellation policies, retries of ordinary failures or application shutdown policy.

## Shape

Core exports inline `draining(block: () -> A): A` to clear an existing interrupt during cleanup and restore it on
return, throw or non-local return. An InterruptedException escaping the block also restores the interrupt flag.
Core exports inline `uninterruptible(block: () -> A): A` for repeatable interruptible waits: retry only
InterruptedException until the wait returns, restore all observed interruption, propagate every other failure.
A wait must be safe to repeat after interruption; this is not a general work-retry API. An optional
`onInterrupted: (InterruptedException) -> Unit` observes each interrupted wait before the next attempt.
Each retry starts with the flag clear, so a wait that throws without clearing it (`checkInterrupted`) cannot spin.

Core also exports inline `onThrow(action: (Throwable) -> Unit, block: () -> A): A` for an acquisition owner
that must fail shared startup promises and release partial work for every escaping throw, including fatal,
interrupt, cancellation and Fail unwinding. The original throwable is rethrown unchanged; observer failure is
suppressed onto it. This is observation/cleanup, not exception-to-value capture or recovery. Resource ExitCase
cannot supply CancellationException/Fail cause because those are intentionally classified Completed.
An observer's InterruptedException is suppressed like any other failure and the interrupt flag is set again.
Since spec 0294 the Fail signal accepts suppression, so an observer failure during Fail unwinding is retained on it.

Core exports `checkInterrupted(): Unit` as the cancellation checkpoint for synchronous adapter code. It throws
InterruptedException when the current thread is interrupted, preserving its interrupt flag; otherwise returns.

## Why this shape

These are resource/shutdown mechanisms without domain types; they belong beside Resource and Scope. Either.catch
must continue propagating interruption. Changing that contract would break cancellation throughout the library.
`Either.catchOrThrow` (spec 0285) captures a value and so cannot replace `onThrow`.

## Stack

- [ ] **claude/spec-0271-draining-20261103** — this spec on its own.
- [ ] **claude/spec-0271-draining-source-20261103** — public primitives, contract tests and core guide entry.
  Done when interruption, repeated waits, non-local returns and original unexpected failure identity are proved.

## Acceptance

`./gradlew :surefold:test :surefold:detekt :surefold:spotlessCheck` and the library's required build gates.
`./gradlew :surefold-compiler:test --tests '*DrainingCompileTest'` and `:surefold-app-pekko:test --tests '*CoreGuideTest'`.

## Open questions

1. Should a fatal observer failure (a `VirtualMachineError`) still be suppressed rather than thrown? Recommended:
   yes, as written; the original throw is what the owner is already unwinding with.
2. Re-landed from the consumer's pinned snapshot (a701994f, f21edb78, a3de7550). The Shape sentences on
   retry flag clearing, observer interrupts and Fail suppression are new here; keep them or revert to the pin text.
