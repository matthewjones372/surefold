# 0271 — interruption-preserving drains

Owner-authorized prerequisite for complete Doorlist raw-catch removal, 1 November 2026.

## Problem

Owners must finish shutdown waits despite interruption and return the interrupt to their caller. Doorlist repeats
this mechanism in its own catch adapter because Scope's internal joining protocol is private.

## Not doing

New concurrency, executors, cancellation policies, retries of ordinary failures or application shutdown policy.

## Shape

Core exports inline `draining(block: () -> A): A` to clear an existing interrupt during cleanup and restore it on
return, throw or non-local return. An InterruptedException escaping the block also restores the interrupt flag.
Core exports inline `uninterruptible(block: () -> A): A` for repeatable interruptible waits: retry only
InterruptedException until the wait returns, restore all observed interruption, propagate every other failure.
A wait must be safe to repeat after interruption; this is not a general work-retry API. An optional
`onInterrupted: (InterruptedException) -> Unit` observes each interrupted wait before the next attempt.

Core also exports inline `onThrow(action: (Throwable) -> Unit, block: () -> A): A` for an acquisition owner
that must fail shared startup promises and release partial work for every escaping throw, including fatal,
interrupt, cancellation and Fail unwinding. The original throwable is rethrown unchanged; observer failure is
suppressed onto it. This is observation/cleanup, not exception-to-value capture or recovery. Resource ExitCase
cannot supply CancellationException/Fail cause because those are intentionally classified Completed.

Core exports `checkInterrupted(): Unit` as the cancellation checkpoint for synchronous adapter code. It throws
InterruptedException when the current thread is interrupted, preserving its interrupt flag; otherwise returns.

## Why this shape

These are resource/shutdown mechanisms without domain types; they belong beside Resource and Scope. Either.catch
must continue propagating interruption. Changing that contract would break cancellation throughout the library.

## Stack

- [ ] **codex/catch-removal-draining** — public primitives, contract tests and core guide entry.
  Done when interruption, repeated waits, non-local returns and original unexpected failure identity are proved.

## Acceptance

`./gradlew :surefold:test :surefold:detekt :surefold:spotlessCheck` and the library's required build gates.
The consumer pins the exact verified source snapshot; no stable release or remote publication is requested.

## Open questions

None: owner requested complete removal, the declared source/current main have only private joinFully, and this
small extraction preserves the existing consumer contract. The scope reviewer confirms this cannot use Either.catch.
