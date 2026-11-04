# 0304 — A background fork that stops its scope

## Problem

A request that holds a lease, sends a heartbeat or refreshes a token while it works starts that loop with
`async { }` and never awaits it. Close interrupts it, which is right. But if the loop fails (the lease is lost,
the heartbeat is refused), nothing tells the body: a plain `async` fork's failure is noticed only when someone
awaits it or when the scope closes (`unnoticedFailure` in `Scope.kt`). The body carries on for as long as it
runs, writing under a lease it no longer holds, and the failure surfaces only afterwards. Today a service
hand-rolls this with a `Promise` that the loop completes and the body polls.

## Not doing

- **Changing `async`.** An unawaited `async` fork keeps the behaviour `docs/core.md` documents.
- **A fork the scope waits for without awaiting it** (Ox's `forkUser`). `await()` before the block returns
  already does that.
- **Restarting a background fork that failed.** Its body can run `Schedule.repeat` or `retry` itself.
- **Supervision trees.** Actors are where restart strategies live.

## Shape

On `Scope<E>`, beside `async`, with no new dependency:

```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fail
import dev.surefold.scope

fun handle(request: Request): Either<LeaseLost, Reply> = attempt {
    scope {
        background { while (true) { lease.renew() ?: fail(LeaseLost); Thread.sleep(5_000) } }
        work(request)
    }
}
```

- `fun background(on: Executor = this.on, block: Scope<E>.() -> Unit): Deferred<Unit>` forks at once, like
  `async(start = Start.Eager)`. Close interrupts and joins it like any other fork.
- **If it fails before close, the scope stops.** A raise or a throw interrupts the thread running the scope's
  body. The scope then answers with the fork's failure rather than with whatever the interrupt made the body
  throw, which is the same rule combinators already follow ("an exception thrown only because a branch was
  interrupted is not reported").
- **A body that swallows the interrupt** still gets the fork's failure when the scope closes, as today. The
  interrupt is the fast path, and close is the guarantee.
- **The interrupt never escapes the scope.** If the fork fails after the body has already left (during close),
  the scope's thread is not interrupted. Its flag after close is what it was before the fork failed.
- **A background fork that returns** has simply ended. Nothing stops.
- `cancel()` on the handle stops it quietly, as with `async`.

## Why this shape

Fail-fast is what a background loop wants, and it is a change of behaviour for a fork someone forgot to await.
A separate entry point keeps `async`'s documented contract. **Alternative:** `async(failFast = true)`. It is one
call fewer to learn, but a boolean on the most-used call in the library. **Alternative:** make every unawaited
fork fail-fast, as Ox's `supervised` does. That is the cleaner model, but it changes behaviour across the
library and needs a migration note of its own. Recommended: `background`, with the second alternative left as an
open question.

## Stack

- [ ] **`spec-0304-background`** — `Scope.background`, the body interrupt through `Fork`'s `ended` hook,
      `BackgroundTest`, a "Background forks" section in `docs/core.md` with a compiled example.
      Done when: a body sleeping for 10 s answers within 100 ms with the `Left` a background fork raised after
      20 ms; a fork failing during close leaves the closing thread's interrupt flag clear; a `replay` exploration
      of the fork failing and the body returning never leaks an interrupt.

## Acceptance

```bash
./gradlew :surefold:test --tests '*Background*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should every unawaited `async` fork become fail-fast instead?** Recommended: not in this spec. If
   `background` proves to be the common case, a later spec can change the default.
2. **Name:** `background`, `daemon` or `forkDaemon`? Recommended: `background`. "Daemon" means something else
   for a JVM thread (it does not keep the process alive).
3. **Should `background` exist on `resourceScope` too**, for a refresher that lives as long as a resource?
   Recommended: no. `surefold-cache`'s own refreshers show it can be built from a scope.
