# 0292 - Retain cleanup failures on the Fail unwind signal

Delegated agent review answers decisions1-3 YES; source adoption remains CONDITIONAL.
Source requires Doorlist603 scope MAIN, upstream spec MAIN, exact source-plan approval and root GO.

## Problem

A Fail raised through Resource keeps the original unwind signal, but that signal disables
suppression. A later release defect is silently lost before an ownership boundary can inspect it.
Spec0265 R4 needs original-signal identity plus distinct cleanup failures during unwinding.

## Not doing

No error on final E/Left, public signal/inspection API, wrapper throwable, logging, new dependency,
Resource rewrite, release-order/ExitCase/routing change, private test hook or crypto/public activation.
No propagation of signal diagnostics through a successfully completed owning attempt.

## Shape

In core/surefold/src/main/kotlin/dev/surefold/fail/Fail.kt, change only Failed's constructor
from Throwable(null, null, false, false) to Throwable(null, null, true, false).
The same opaque signal now accepts suppression; its stack remains empty and its own scope/error
stay unchanged. Resource releases still run in reverse order, all releases still run, and
ExitCase.Completed still describes Fail. Nested foreign attempts rethrow the SAME signal.
The owning attempt still answers the SAME E on Left; no cleanup error is added to E or Either.
For Throwable-valued E, its pre-existing suppressed list stays unchanged.
Boundary proof observes the Throwable BEFORE its owning attempt consumes it, then rethrows it.
Public API sketch (inside a test with explicit imports from dev.surefold.fail / dev.surefold):
```kotlin
val error = Any()
val answer = attempt<Any, Unit> {
    resourceScope {
        onRelease { _ -> Unit }
        fail(error)
    }
}
```
The test separately asserts (answer as Either.Left).value === error. No new API is proposed.

## Why this shape

Enabling the existing signal's suppression is the smallest change that lets Resource retain
cleanup without replacing Fail or changing E. Wrapping or rewriting Resource/routing is wider.

## Stack

- [ ] **codex/p04-h-fail-cleanup-spec-20261103** - this reviewed spec and0265 dependency amendment only.
- [ ] **codex/p04-h-fail-cleanup-source-20261103** - one constructor flag, focused lifecycle tests; main target<10.
      Done after reviewed source plan, meaningful RED, full gates and successful containing-main.
B1/B2 source follows acceptance of this prerequisite and separate approved split/source plans.

## Acceptance

Add tests in existing core .../src/test/kotlin/dev/surefold/ResourceTest.kt and fail/FailTest.kt.
Use public attempt/fail/resourceScope, shouldThrow<Throwable> and identity assertions to observe
the opaque signal before its owning attempt handles it, then rethrow that SAME signal; no raw
catch, direct Failed construction or reflection is needed. Assert SAME signal across inner/outer finally,
empty stack, cleanup suppression by exact identity/order, every release attempted in reverse order,
Completed exits and final Left containing SAME E. With a nested foreign attempt, its normal tail
must not execute; only the owning attempt produces Left(E). Also preserve ordinary throwing,
InterruptedException and CancellationException identity, cleanup suppression/order and release behavior.
A Throwable-valued E retains its own prior suppressed list; preserve existing fail routing.
Reverting true to false must compile and fail suppression assertions. No runtime result is claimed.
```bash
./gradlew :surefold:test --tests '*ResourceTest' --tests '*FailTest'
./gradlew :surefold:check :surefold-app:check
./gradlew spotlessApply && ./gradlew build
```
Retain affected-module checks, typed detekt, dependency/Kover gates, exact-source review and
exact PR/actual-main receipts. No check or source edit has occurred for this draft.

## Open questions

1. YES - retain transient suppression on opaque Fail while final Left(E) stays unchanged.
2. YES - extend the two existing suites above; no helper/framework.
3. YES - amend0265's dependency/stack only, preserving all24 laws and E/S/D modes.
