# 0274 — SurefoldLocal on ScopedValue

## Problem

Every `SurefoldLocal` (log annotations, spans, the bound logger and clock, OpenTelemetry's
context, OpenFeature's) lives in one `ThreadLocal` map in `Bindings`. 0017 chose that
because `ScopedValue` was a preview on the JDK 21 floor. The floor is now 25, where
`ScopedValue` is final (JEP 506). 0037 open question 3 and 0040's dropped `locals` entry
kept `ThreadLocal` for one reason: `attach`, which OpenTelemetry's `ContextStorage`,
OpenFeature's propagator and `surefold-cache`'s `ownWorker` call, binds without a block.

Nobody has measured what the `ThreadLocal` costs on a log line. The more likely cost is
`Slf4jLogger.log`, which copies the MDC map per line.

## Not doing

- No `StructuredTaskScope` in `surefold`. It is still a preview API on 25.
- No change to `SurefoldLocal`'s public API: `get`, `unbound`, `locally`, `attach`.
- No change to how a fork inherits: the opener's snapshot, rebound on the forked thread.
- The MDC copy in `surefold-slf4j` is its own spec if the benchmark points there.

## Shape

There is nothing new at a call site. Inside `Bindings`:

```kotlin
internal object Bindings {
    private val current: ScopedValue<Frame> = ScopedValue.newInstance()
    private val root = ThreadLocal<Frame>() // only threads nothing has bound on: main, a pool's own

    fun snapshot(): Map<SurefoldLocal<*>, Any?> = frame()?.bound ?: emptyMap()

    fun <B> under(bound: Map<SurefoldLocal<*>, Any?>, block: () -> B): B =
        ScopedValue.where(current, Frame(bound)).call(ScopedValue.CallableOp<B, RuntimeException> { block() })

    // JDK 25's orElse refuses null, so isBound first.
    private fun frame(): Frame? = if (current.isBound) current.get() else root.get()
}

/** One binding's map. `attach` replaces [bound] on the thread that owns the frame and nowhere else. */
internal class Frame(var bound: Map<SurefoldLocal<*>, Any?>)
```

`attach` replaces the current frame's map and `Detach` puts the previous map back. Leaving
`under` drops the whole frame, as leaving the block drops the restored map today.

## Why this shape

With a frame, `locally`, logging and every fork site read a `ScopedValue`. A thread reads the
`ThreadLocal` fallback only when nothing has bound on it, so `attach` still works for
OpenTelemetry and OpenFeature. A frame is never shared across threads: a fork copies the
snapshot into a new frame. A `StructuredTaskScope` subtask inherits its parent's frame, so an
`attach` there would reach the parent. Only the unpublished `surefold-structured` opens one,
and it binds no `SurefoldLocal`.

Alternative: pure `ScopedValue` with `attach` removed. That moves OpenTelemetry and OpenFeature
back to their own `ThreadLocal`s, which forks cannot see, and those integrations exist to fix
exactly that. Not recommended.

Alternative: keep `ThreadLocal` and fix the MDC copy only. Recommended if the benchmark in the
first stack entry shows less than 10% on a log line.

## Stack

- [ ] **`spec-0274-bench`**: a JMH benchmark of `annotations.get()`, `logAnnotated { }` and
      one fork's inheritance, plus a line through `Slf4jLogger`. Today's `Bindings` vs a
      `ScopedValue` prototype.
      Done when: the numbers are in this spec and the maintainer says go or stop.
- [ ] **`spec-0274-bindings`**: `Bindings` on `ScopedValue` with a `Frame`, root fallback.
      Done when: `SurefoldLocalTest`, `AnnotationsTest`, `ContextAcrossAForkSuite`,
      `FeatureContextSuite` and the cache suites pass unchanged, and a new test shows a
      forked branch reading the opener's `attach`ed value.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement both": each taken as recommended.

1. **Gate the swap on the benchmark?** Yes. The numbers go under Stack before the swap
   lands. On HotSpot, `ThreadLocal.get` and a cached `ScopedValue.get` both take
   nanoseconds, and every `locally` and fork now pays for a `where(...).call`.
2. **Where does the benchmark live?** A new `bench/surefold-core-benchmarks`, with
   `surefold-actor-benchmarks`' JMH wiring.
3. **Is the root `ThreadLocal` fallback acceptable?** Yes. Rejecting `attach` outside a
   `locally` would break OpenTelemetry's root spans on `main`.
4. **Does `surefold-cache`'s `ownWorker` attach from inside a fork's frame?** It does,
   inside `SingleFlight`'s `under`. The attach lasts for the rest of that frame, as it does
   today, and `SurefoldLocalTest` gets a case for it.
