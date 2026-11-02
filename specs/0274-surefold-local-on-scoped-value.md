# 0274 — SurefoldLocal on ScopedValue

**Stopped (maintainer, 2026-11-02).** The benchmark below showed the swap loses, so it was built,
measured and reverted. `Bindings` stays on `ThreadLocal`. The benchmark and `SurefoldLocalTest`'s
`attach` cases stay.

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

- [x] **`spec-0274-bench`**: a JMH benchmark of `annotations.get()`, `logAnnotated { }` and
      one fork's inheritance, plus a line through `Slf4jLogger`. Today's `Bindings` vs a
      `ScopedValue` prototype.
      Done when: the numbers are in this spec and the maintainer says go or stop.
- ~~**`spec-0274-bindings`**: `Bindings` on `ScopedValue` with a `Frame`, root fallback.~~
      Built, measured and reverted: see Numbers.
      Done when: `SurefoldLocalTest`, `AnnotationsTest`, `ContextAcrossAForkSuite`,
      `FeatureContextSuite` and the cache suites pass unchanged, and a new test shows a
      forked branch reading the opener's `attach`ed value.

### Numbers (`spec-0274-bench`)

`LocalsBenchmark`, 2026-11-02, JDK 25.0.5 (Temurin), 4 vCPU cloud container, 1 fork, 3 warmup and 5
measured iterations of 1 s, `-prof gc`. Before is `main`'s `SurefoldLocal.kt`, `Scope.kt` and `Transact.kt` on
this branch; after is this branch. The container is noisy (`surefold-actor-benchmarks/README.md`): read
the times as direction, and the bytes per operation, which barely vary, as the firmer number.

| Benchmark | Before ns | After ns | Change | Before B/op | After B/op |
|---|---|---|---|---|---|
| `readUnbound` | 2.3 ± 0.2 | 2.6 ± 0.4 | +14% | 0 | 0 |
| `bindAndRead` | 33.3 ± 4.2 | 49.9 ± 2.8 | +50% | 88 | 128 |
| `readsInsideABinding` (100 reads) | 382.8 ± 141.4 | 509.9 ± 126.6 | +33% | 88 | 128 |
| `annotatedLogLine` | 449.5 ± 833.2 | 449.7 ± 98.7 | 0% | 904 | 1016 |
| `forkInheritsABinding` | 43,822 ± 12,878 | 44,791 ± 8,049 | +2% | 1394 | 1714 |
| `forkOnAPool` | 48,843 ± 11,594 | 45,432 ± 11,958 | −7% | 854 | 990 |
| `transactOnce` | 650.7 ± 83.7 | 679.6 ± 123.0 | +4% | 1864 | 1888 |
| `transactNested` | 636.7 ± 102.8 | 655.9 ± 77.6 | +3% | 1880 | 1888 |

The swap loses. A binding costs about 17 ns and 40 bytes more, a read about 1 ns more, a log line
112 bytes more, and a fork 136 to 320 bytes more. A fork does not allocate less, as Problem expected.
`ScopedValue`'s `where(...).call` allocates more than the `ThreadLocalMap` a new virtual thread
used to need. Fork and `transact` times are within noise. Recommended, and taken: stop. `Bindings` stays on
`ThreadLocal`, `spec-0274-bindings` is reverted, and the benchmark stays as the record of why.

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
