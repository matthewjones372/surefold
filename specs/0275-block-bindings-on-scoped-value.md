# 0275 — Block bindings on ScopedValue

**Stopped (maintainer, 2026-11-02),** with 0274. Its benchmark measured a `ScopedValue` binding
at about 17 ns and 40 bytes more than a `ThreadLocal` set and restore, and
`forkOnAPool` and `transact` showed no gain. All five stay on `ThreadLocal`.

## Problem

Besides `Bindings` (0274), five `ThreadLocal`s in surefold do what `ScopedValue` was made for.
Each is set for the length of one block and restored or removed when the block ends:

| Where | What it holds | Read |
|---|---|---|
| `Scope.kt` `runningFor` | the executor running a fork's body | on every `await` |
| `Transact.kt` `running` | the attempt in progress, so a nested `transact` joins it | per `transact` |
| `surefold-actor` `Runtime.kt` `stepping` | the actor whose turn this thread runs | on every `tell` and `offer` |
| `surefold-stream` `Waiting.installed` | a turn-taking backend's `Waiting` | per waiting source |
| `surefold-actor-remote` `Remote.written` | replies written while one frame is encoded | per encoded frame |

A virtual thread that sets a `ThreadLocal` allocates its own `ThreadLocalMap`, and every
fork runs on a new virtual thread. A missed `finally` leaves the value behind for whatever
the pool runs next. A `ScopedValue` binding has neither problem, and on JDK 25 it is final
(JEP 506).

## Not doing

- `surefold-assert`'s `lazyClues`: `withClue` is `inline`, and a lambda handed to
  `ScopedValue` would stop a test using `return` from inside its block.
- `surefold-junit`'s `leasing`: test-only code, so nothing to gain.
- SLF4J's MDC: SLF4J owns that `ThreadLocal`, not surefold.
- No public API change, and no shared helper published from `surefold` for the other
  modules. Each binds through the JDK call directly.

## Shape

Each block becomes a `ScopedValue.where(...).call`. Nothing new at a call site:

```kotlin
private val runningFor: ScopedValue<Executor> = ScopedValue.newInstance()

private fun run() {
    if (!claim(Thread.currentThread(), own = on === VirtualThreads)) return
    ScopedValue.where(runningFor, on).call(ScopedValue.CallableOp<Unit, RuntimeException> { body() })
}
```

A read that used `get()` returning null becomes `if (x.isBound) x.get() else null`. JDK 25's
`orElse` refuses a null argument.

## Why this shape

`ScopedValue` ties each binding to the block that made it, and none of the five needs to
outlive its block. Binding still costs an allocation. `stepping` binds once per actor
activation, on runner threads that live a long time and so already pay for their map
once, which makes it the one place this could be slower.

Alternative: leave the long-lived runner's `stepping` on `ThreadLocal`. Recommended only if
the actor benchmarks show a loss.

## Stack

- ~~**`spec-0275-core`**: `runningFor` and `Transact.running`.~~ Built and reverted.
      Done when: the core suites pass unchanged.
- ~~**`spec-0275-modules`**: `stepping`, `Waiting.installed`, `Remote.written`.~~ Built and reverted.
      Done when: the actor, actor-remote and stream suites pass unchanged.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Gate `stepping` on `surefold-actor-benchmarks`' tell benchmarks?** Taken as
   recommended: measure, and revert that one binding if it loses.
2. **One stack entry for core and one for the other modules, or one per `ThreadLocal`?**
   Taken as recommended: two. Each change is a few lines.
3. **Bind `runningFor` together with the fork's `Bindings` frame in one `where(...).where(...)`?**
   Taken as recommended: no. They are bound in different places, and merging them would
   couple `Scope` to `Bindings`' internals for one allocation.
