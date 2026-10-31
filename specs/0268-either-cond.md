# 0268 - Construct an Either from a condition

Approved by the owner, 31 October 2026, following the lazy-branch API proposed in chat.

## Problem

Callers constructing an Either from one Boolean repeat `if (...) value.right() else error.left()`.
A small constructor expresses that decision directly without opening an attempt scope.

## Not doing

No exception capture, validation accumulation, retries, eager overloads, default Unit branch, new dependency,
remote publication or stable release. `ensure` remains the check inside an attempt; independent field checks
continue to accumulate. Existing APIs and consumer-specific policies stay unchanged.

## Shape

Add this public inline companion constructor:

```kotlin
inline fun <E, A> cond(condition: Boolean, right: () -> A, left: () -> E): Either<E, A> =
    if (condition) Right(right()) else Left(left())
```

Call syntax: `Either.cond(condition = available, right = { value }, left = { error })`.
True invokes right exactly once and wraps its result in Right; false invokes left exactly once and wraps it
in Left. Never invoke the other callback. Support nullable payloads on either side and infer both types.
Callbacks' exceptions, interruption, cancellation, fatal errors and typed failure unwinding propagate unchanged.
This constructor performs no catch. Inline lambdas retain ordinary Kotlin non-local-return behavior.

## Why this shape

Keep Scala's familiar cond name, Boolean-first and success-before-error order. Kotlin lambdas provide the lazy
branches. Eager values would evaluate unselected work; a builder or overload family would add choices without
improving this simple case. The owner approved both required lazy branches and no initial overloads.

## Stack

- [ ] **`codex/0268-either-cond`** - constructor, focused tests and compiled guide example.
      Done when: selection, laziness, identity, nullable payloads, inference and propagation pass all checks.

Build from consumer snapshot source c6d5465d so a new local source-pinned snapshot retains its existing
capabilities. Consumer adoption has its own approved committed spec and preserves its snapshot verification
and bootstrap guarantees. No unrelated library source or provider operations are changed.

## Acceptance

Write tests before implementation; show that the new constructor is absent, then verify both branch counts,
unselected failure laziness, payload identity/nulls, inferred types, non-local return and exception/typed-failure
propagation. Compile the core-guide example. Run formatting and the full build before local snapshot publication.

```bash
./gradlew :surefold:test --tests '*EitherCondTest*'
./gradlew spotlessApply
./gradlew build --continue --no-parallel --max-workers=2
```

## Open questions

Resolved by owner approval: use cond; require two lazy branches; no overloads; publish only a traceable local
snapshot and adopt appropriate consumer constructors. No open behavior decision remains.
