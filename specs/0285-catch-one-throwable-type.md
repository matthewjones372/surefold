# 0285 — Catch one throwable type

## Problem

`Either.catch { }` puts every non-fatal throwable on the left as `Either<Throwable, A>`.
`recover(KClass)` picks one type out of that left afterwards, but it keeps `E = Throwable`, and
anything it doesn't match stays on the left. Nothing catches one type onto the left and lets
everything else propagate.

Doorlist, the first consumer, writes this by hand about 54 times in main code:

```kotlin
Either.catch { Instant.parse(raw) }.getOrElse { thrown -> if (thrown is DateTimeException) null else throw thrown }
```

That is a narrow try/catch spelled the long way to get past Doorlist's raw-catch ban, and the
left side's type is lost on the way.

## Not doing

- Catching several unrelated types at once (`catchOrThrow<A, B>`). Callers can use a common
  supertype or nest two calls.
- Changing `Either.catch`, `recover` or `nonFatalOrThrow`.
- Migrating Doorlist. That is Doorlist's own change, after this one is released.

## Shape

```kotlin
// in Either.Companion
inline fun <reified T : Throwable, A> catchOrThrow(block: () -> A): Either<T, A>

val at: Instant? = Either.catchOrThrow<DateTimeException, _> { Instant.parse(raw) }.getOrNull()
```

- What `block` returned goes on the right.
- A thrown `T`, or a subclass of `T`, goes on the left with the same identity.
- Any other non-fatal throwable is rethrown unchanged, as the same instance.
- Anything fatal, and a `fail` leaving its block, goes up as with `Either.catch`: the throwable
  passes through `nonFatalOrThrow()` before the `is T` check.

Kotlin does not allow `catch (e: T)` on a type parameter, so the body is one
`catch (thrown: Throwable)` that calls `nonFatalOrThrow()` and then checks `is T`. That keeps
`EatenFailureChecker` (spec 0041) satisfied. It is not `catch { }.mapLeft { throw it }`, which
would build a `Left` only to discard it.

## Why this shape

The reified type reads like the `catch` clause it replaces and gives the left a precise type.
The name is Arrow's (`arrow.core.Either.catchOrThrow`), so `docs/arrow.md` can map it one to
one. The alternative is `catchOrThrow(type: KClass<T>, block)`, which matches `recover(KClass)`
and also works from a non-inline caller holding a `KClass`. It is noisier at every call site,
and nothing needs it yet. Recommended: reified.

## Stack

- [ ] **`spec-0285-catch-or-throw`**: `Either.catchOrThrow` in `Either.kt`, `EitherCatchOrThrowTest`,
      a row in `docs/cookbook.md`'s need-to-API table, a compiled example in `docs/core.md`, the
      Arrow mapping in `docs/arrow.md` and a line in `llms.txt`.
      Done when: tests show a right on success, a left on `T` and on a subclass of `T`, a
      non-`T` rethrown as the same instance, `OutOfMemoryError` and `InterruptedException`
      rethrown even when `T` is `Throwable`, and a `fail` inside reaching its `attempt`.
      `./gradlew build` is green.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Name: `catchOrThrow<T>` or `catching<T>`?** `catching` is shorter and reads well
   (`Either.catching<IOException, _> { }`), but it sits too close to `catch` and isn't Arrow's
   name. Recommended: `catchOrThrow`.
2. **Reified only, or a `KClass` overload as well?** Recommended: reified only. Add the overload
   when a caller holding a `KClass` turns up.
3. **What does `T = InterruptedException` (or `CancellationException`) answer?** With
   `nonFatalOrThrow()` first, it never answers a left and the interrupt keeps propagating.
   That's surprising, but putting it on the left would drop the interrupt. Recommended: fatal
   first, and say so in the KDoc.
4. **Does the `_` for `A` at the call site bother anyone?** Kotlin can't infer one type argument
   and take the other explicitly. Swapping to `<A, reified T>` doesn't help. Recommended: accept
   `_`, as Arrow does.
