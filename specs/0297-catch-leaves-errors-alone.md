# 0297 — Catch leaves errors alone

## Problem

`Either.catch { }` catches `Throwable` and passes it through `nonFatalOrThrow()`, which rethrows a
denylist: VM errors, linkage errors, interrupts, cancellations and a `fail` leaving its block.
Every other `Error` lands on the left as if a caller could handle it: `AssertionError` (and
JUnit's `AssertionFailedError`), Kotlin's `NotImplementedError` from `TODO()`, and any custom
`Error`. In `Decoder.decode` a deserializer that hits a `TODO()` becomes a `DecodeError` and is
dead-lettered; in `Producer.send` it becomes a failed future. A bug is reported as bad input.

The list comes from Arrow, which took it from Scala's `NonFatal`. A denylist fails open: an
`Error` nobody listed is treated as recoverable. In a library whose users run it under tests, the
worst case is an assertion caught into a value and a test that passes.

## Not doing

- Changing `nonFatalOrThrow`. The actor runtime, `JobRun`, stream fan-in and remote actors call
  it on purpose with `Throwable`, so an assertion in one actor fails that actor, not its runtime.
- Changing `catchOrThrow<T>`. With `T = Throwable` it stays the explicit way to catch an `Error`.
- Migrating the private service from spec 0285.

## Shape

```kotlin
// in Either.Companion
inline fun <A> catch(block: () -> A): Either<Exception, A> =
    try {
        Right(block())
    } catch (thrown: Exception) {
        Left(thrown.nonFatalOrThrow() as Exception)
    }
```

- What `block` returned goes on the right; a thrown `Exception` goes on the left, same instance.
- Every `Error` goes up unchanged, whether or not `nonFatalOrThrow` lists it.
- `InterruptedException` and `CancellationException` are `Exception`s, so `nonFatalOrThrow()`
  still rethrows them. A `fail` is a bare `Throwable`, so the catch never sees it.
- `Either` is covariant in `E`, so callers holding `Either<Throwable, A>` keep compiling.
- `Explore.inOrder` in `surefold-replay` wants assertion failures; it moves to
  `Either.catchOrThrow<Throwable, _>(body)`.

## Why this shape

`Exception` versus `Error` is the JVM's own line between "a caller may handle this" and "something
is broken", and an allowlist fails safe: a new `Error` goes up unless a call site opts in. The
cost is the rare `Error` that really is recoverable (`java.io.IOError`,
`ServiceConfigurationError`); `catchOrThrow<IOError, _>` covers it where it is expected. The
alternative is to make `nonFatalOrThrow` treat every `Error` as fatal, which fixes `catch` too
but changes every runtime boundary above. Recommended: narrow `catch` only. This departs from
Arrow on purpose, and `docs/arrow.md` says so.

## Stack

- [ ] **`spec-0297-catch-exception`** — `Either.catch` narrowed in `Either.kt`; `Explore.kt` on
      `catchOrThrow<Throwable, _>`; tests in `EitherTest`; `docs/arrow.md` row split and a
      "Where Surefold differs" bullet; the `Either.catch` line in `docs/core.md` and `llms.txt`.
      Done when: tests show `AssertionError`, `NotImplementedError` and `OutOfMemoryError` thrown
      through `catch` unchanged, an `IllegalStateException` on the left as the same instance,
      `InterruptedException` and `CancellationException` rethrown, a `fail` reaching its
      `attempt`, and `explore`/`replay` still naming an order whose body fails an assertion.
      `./gradlew build` is green.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Left type `Exception` or keep `Throwable`?** `Exception` says what the left can hold and
   costs nothing, thanks to covariance. Recommended: `Exception`.
2. **Should `Producer.send` keep failing its future on an `Error` from `client.send`?** After
   this, the `Error` comes out of `send()` itself. Recommended: let it, since a broken serializer
   is a bug, and note it in the Kafka changelog.
3. **Should `catchOrThrow<T>` also refuse `Error`s unless `T` names one?** It already does in
   effect: an `Error` that is not a `T` is rethrown. Recommended: no change.
4. **Is the `as Exception` cast in the sketch acceptable?** `nonFatalOrThrow` returns `Throwable`.
   An `Exception`-typed overload of it would avoid the cast. Recommended: the cast, since
   `nonFatalOrThrow` returns its receiver.
