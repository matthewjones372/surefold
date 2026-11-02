# 0280 — A log line that copies nothing

## Problem

0279's first profile, `LocalsBenchmark.annotatedLogLine` with `-Pjfr`, put about half of a log
line's CPU in copying hash maps: `HashMap.putMapEntries` 21%, `HashMap.resize` 10%,
`LinkedHashMap.afterNodeInsertion` 13%. The line allocates 904 to 968 bytes. Three copies cause it:

1. **`logAt`** builds `annotations.get() + spans.get().elapsedAt(at)` on every line. With no span
   open, which is the usual case, that still makes an empty map for the spans and copies every
   annotation into a new map to add nothing.
2. **`SurefoldLocal.locally`** binds with `Bindings.snapshot() + (this to value)`, which copies the
   map of every bound local (logger, clock, annotations, spans, a trace) to change one of them.
3. **`logAnnotated`** copies the annotation map to add its pairs. This one is needed, since a
   binding must not change the map its opener sees.

Every service that logs pays the first copy on each line, and the second on each `locally`,
`logAnnotated` and `logSpan`.

## Not doing

- Not moving `Bindings` off `ThreadLocal`. 0274 measured that and stopped.
- No change to `LogLine`, `Logger`, `logAnnotated` or any public signature.
- The third copy stays (open question 3).
- `surefold-slf4j`'s per-line MDC copy is that module's own concern.

## Shape

Nothing new at a call site. In `logAt`, add the spans only when there are any:

```kotlin
val open = spans.get()
val annotated = if (open.isEmpty()) annotations.get() else annotations.get() + open.elapsedAt(at)
```

`Bindings` holds a chain in place of a map. A binding pushes one link, `get` walks to the first
link for its local, and a fork captures the head as it does the map today:

```kotlin
internal class Bound(val local: SurefoldLocal<*>, val value: Any?, val next: Bound?)
```

## Why this shape

The `logAt` change removes a copy that adds nothing, and costs one `isEmpty`. The chain makes a
binding O(1) and a fork's capture O(1). A lookup becomes a walk, but over what a thread has bound,
which is a handful of links. A rebound local leaves its older link further down the chain, so the
walk is as deep as the nesting.

Alternative: a small array indexed by each local's id. A lookup is O(1), but a binding still
copies the array, and the index grows with every `surefoldLocal` ever made, including those of
finished tests. Not recommended.

## Stack

- [ ] **`spec-0280-line`**: `logAt` without the spans copy.
      Done when: `annotatedLogLine` allocates less, and `AnnotationsTest` passes unchanged.
- [ ] **`spec-0280-chain`**: `Bindings` as a chain of `Bound`.
      Done when: `SurefoldLocalTest`, `AnnotationsTest`, `ContextAcrossAForkSuite`,
      `FeatureContextSuite` and the cache suites pass unchanged. `LocalsBenchmark` is recorded
      before and after, with `-Pjfr`'s hot methods.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark" -Pjfr
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0280": each taken as recommended.

1. **May `LogLine.annotations` be the bound map itself, not a copy?** The map is never written
   after it is made. A backend that casts it to `MutableMap` and writes to it would change what
   later lines see. Recommended: yes, and say in `LogLine`'s KDoc that the map is read-only.
2. **A chain or an indexed array?** Recommended: the chain, as above.
3. **Should `logAnnotated` stop copying too,** keeping its pairs as links and building the map
   once per line? That moves the copy from the binding to every line written inside it, and a
   line is usually written more often than its annotations are bound. Recommended: no.
4. **Should the benchmark also measure a line under a logger bound only once,** as a service
   binds it? `annotatedLogLine` binds the logger on every call, which counts copy 2 twice.
   Recommended: yes, as `logLine`, so the numbers show what a service pays.
