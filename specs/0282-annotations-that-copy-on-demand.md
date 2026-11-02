# 0282 — Annotations that copy only when read

## Problem

After 0280, the last map copy on a log line's path is `logAnnotated`'s own:
`annotations.locally(annotations.get() + pairs, block)`. It builds a new `LinkedHashMap` with an
entry object for every pair already bound and every pair added, each time a block is annotated.
0280's profile puts what is left of the map work there (`HashMap.putVal` 11%, `putAll` 5%), and
`annotatedLogLineUnderABoundLogger` still allocates 272 bytes.

The copy is paid whether or not anything reads it. A service that annotates every request with
`logAnnotated("request_id" to id)` and logs only on failure builds one map per request that no
line ever reads. 0280's open question 3 kept the copy, because building the map at each line
instead would cost more wherever a block writes several lines.

## Not doing

- No change to `logAnnotated`'s signature, to `LogLine`, or to the order a backend sees the
  pairs in. Outer pairs come first, and an inner pair with the same key keeps the outer key's
  place with the inner value, as `Map + pairs` does today.
- `logSpan`'s list copy. Spans are rarer, and the list holds one element per span.
- `surefold-slf4j`'s per-line MDC copy.

## Shape

Nothing new at a call site. Inside, the bound annotations become links, one per `logAnnotated`,
each building its map the first time a line or a carrier asks and keeping it:

```kotlin
internal class Annotated(val pairs: Array<out Pair<String, String>>, val outer: Annotated?) {
    // Built once, by whichever thread asks first. Two threads asking at once build equal maps.
    @Volatile private var built: Map<String, String>? = null
    fun map(): Map<String, String> = built ?: (outer?.map().orEmpty() + pairs).also { built = it }
}
```

`logAnnotated` pushes one link, which is no copy. `logAt` and `AnnotationCarrier.capture` call
`map()`. That builds the map at most once per annotated block, and only if something reads it.
Every later line in the block, and every fork, reuses it.

## Why this shape

A block that writes no line copies nothing, and a block that writes several copies once, as
today. A block that writes exactly one line pays the same copy at the line instead of at the
binding: no worse. The alternative, a small array-backed map that is cheaper to copy than a
`LinkedHashMap`, makes every copy cheaper but keeps every copy. It could be stacked on this
later if the numbers ask for it (open question 2).

## Stack

- [x] **`spec-0282-bench`**: `LocalsBenchmark.annotatedWithoutALine` (a block annotated, nothing
      logged) and `manyLinesUnderOneAnnotation` (ten lines in one block), measured on `main`.
      Done when: the numbers are in this spec.
- [x] **`spec-0282-links`**: `Annotated` links behind `annotations`, with `logAt` and the carrier
      reading `map()`.
      Done when: the suites that read annotations pass unchanged: `AnnotationsTest`,
      `Slf4jLoggerSuite`, `CarriedSuite`, `RemoteCarriedSuite`, `KeptCarriedSuite` and the jobs
      suites. `annotatedWithoutALine` allocates no map, and `manyLinesUnderOneAnnotation` is no
      slower.

### Numbers

`LocalsBenchmark`, 2026-11-02, JDK 25.0.5, 4 vCPU cloud container, 1 fork, 3 warmup and 5 measured
iterations of 1 s, `-prof gc`. Before is 0280's `main`.

| Benchmark | Before ns | After ns | Change | Before B/op | After B/op |
|---|---|---|---|---|---|
| `annotatedWithoutALine` | 67.6 ± 9.8 | 31.6 ± 4.9 | −53% | 272 | 120 |
| `manyLinesUnderOneAnnotation` (10 lines) | 707.6 ± 239.4 | 645.0 ± 119.6 | within noise | 272 | 296 |
| `annotatedLogLineUnderABoundLogger` | 147.0 ± 95.2 | 142.7 ± 28.2 | within noise | 272 | 296 |
| `annotatedLogLine` | 170.2 ± 41.1 | 207.0 ± 36.5 | +22%, at the noise's edge | 328 | 352 |
| `logLine` | 59.2 ± 19.7 | 58.7 ± 14.9 | within noise | 0 | 0 |

A block that logs nothing now costs half as much, with no map. A block that does log pays 24 bytes
more than before: the `Annotated` link. "No worse" under Why this shape was wrong by that link, and
a block with exactly one line is where it shows. Open question 2's smaller map is where those
bytes could come back.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.(annotated|manyLines|logLine)" -Pjfr
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0281": each taken as recommended.
This spec was drafted as 0281 and renumbered when 0281 went to snapshots on GitHub Packages.

1. **Should the cache be `@Volatile`, or a plain field?** A fork reads its opener's link from
   another thread. Two threads building the same map is harmless, but a plain field could let a
   thread see a reference to a map that is not fully built. Recommended: `@Volatile`. Its cost is
   one read per line.
2. **Stack a small array-backed map on top?** Recommended: not in this spec. Decide after
   `spec-0282-links`'s numbers, if a block's single line still dominates.
3. **`AnnotationCarrier.within` replaces what was bound with what it received. Should it bind one
   link with no outer link?** That keeps today's replace, not merge, behaviour. Recommended: yes.
   It still filters and strips the prefix from what it received, so it saves no copy there.
