# 0284 — One lookup per log line

## Problem

After 0283, a JFR profile of `LocalsBenchmark` (`-Pjfr`) shows where a log line's CPU goes:

| Hot method | `logLine` | `annotatedLogLineUnderABoundLogger` |
|---|---|---|
| `SurefoldLocal.get` and `unbound` | 45% | 40% |
| `VM.getNanoTimeAdjustment` (the wall clock, for `Instant.now()`) | 42% | 19% |
| `SmallMap.of` and `Annotated` (0282 and 0283) | n/a | 24% |

`logAt` reads four locals: `clock`, `spans`, `annotations` (twice when a span is open) and
`logger`. Each `get` is a `ThreadLocal` lookup, a walk down the binding chain, and, for a local
bound nowhere, a call to its initial-value lambda. A line that reads four locals repeats all of
that four times. The `ThreadLocal` lookup is inlined into `get`, which is why the profile charges
it there.

## Not doing

- The wall clock. `LogLine.at` is a public `Instant`, so a line has to read the clock. Not reading
  it, for a backend that stamps its own time as SLF4J's do, would change `LogLine`. That is a
  public API change and a spec of its own (open question 3).
- Any change to `SurefoldLocal`'s public API.
- `topLevel`'s `ClassValue` lookup. It does not show in the profile.

## Shape

Nothing new at a call site. `SurefoldLocal` gains an internal read from a chain already in hand,
and `logAt` takes one snapshot for the line:

```kotlin
internal fun from(head: Bound?): A  // what get() answers, given the chain get() would have looked up

internal fun logAt(level: LogLevel, message: String, cause: Throwable?, caller: Class<*>) {
    val bound = Bindings.snapshot()
    val at = clock.from(bound).now()
    val open = spans.from(bound)
    ...
    logger.from(bound).log(line)
}
```

`get()` becomes `from(Bindings.snapshot())`, so the two can never answer differently.

## Why this shape

One `ThreadLocal` lookup per line instead of four, with the four walks kept: each walks only
the few links a thread has bound. A walk that finds all four locals in one pass would save the
remaining walks too. But it needs `logAt` to know the chain's layout, and the lookups are the
larger cost. Not recommended until a profile says the walks matter.

## Stack

- [x] **`spec-0284-from`**: `SurefoldLocal.from`, with `get` and `logAt` reading through it.
      Done when: `SurefoldLocalTest`, `AnnotationsTest` and `Slf4jLoggerSuite` pass unchanged.
      `logLine` and `annotatedLogLineUnderABoundLogger` are recorded before and after, with
      `-Pjfr`'s hot methods.

### Numbers

`LocalsBenchmark`, 2026-11-02, JDK 25.0.5, 4 vCPU cloud container, 3 forks, 5 warmup and 10 measured
iterations of 1 s. Before is 0283. A first run with 1 fork and 5 iterations was too noisy to read:
it moved `annotatedWithoutALine` by 24%, and that benchmark never reaches `logAt`.

| Benchmark | Before ns | After ns | Change |
|---|---|---|---|
| `logLine` | 59.7 ± 3.1 | 53.5 ± 4.4 | −10% |
| `annotatedLogLineUnderABoundLogger` | 137.5 ± 11.9 | 131.7 ± 10.5 | within noise |

Allocation is unchanged (0 and 240 B/op). `logLine` with `-Pjfr` afterwards: 690 CPU-time samples
kept, 98 lost. Reading locals fell from 45% of the line to 30% (`from` 21%, `unbound` 9%). The wall
clock is now 66% (`getNanoTimeAdjustment` 55%, `Clock.currentInstant` 11%). That makes open
question 3, a line that need not read the clock, the next thing to reduce.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.(logLine|annotated)" -Pjfr
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0284": each taken as recommended.

1. **Should `unbound()` keep calling its initial-value lambda each time?** It does today, so a
   local bound nowhere builds its initial value on every read: `{ SystemClock }`,
   `{ emptyList() }`, or `{ discovered }` for the logger. Recommended: keep it. Caching would change
   what `unbound()` does for every local, not only these three, and these three are already
   cheap. Revisit if the profile still shows `unbound` after this spec.
2. **Should anything else that reads several locals at once read through `from`?** Recommended:
   no. Only `logAt` runs on every line. Other callers can move to it when a profile names them.
3. **Should `LogLine.at` become optional,** so a line need not read the clock for a backend that
   stamps its own time? That would make it a function or a nullable `Instant`, which changes
   public API, and the clock read is up to 42% of a plain line. Recommended: a separate spec,
   written after this one lands.
