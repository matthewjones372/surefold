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
A wait must be safe to repeat after interruption; this is not a general work-retry API.

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
