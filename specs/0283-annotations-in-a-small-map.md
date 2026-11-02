# 0283 — Annotations in a small map

## Problem

0282 builds an annotated block's map only when something reads it. When something does, the map
is a `LinkedHashMap` built by `Map + pairs`. For the one or two pairs a block usually carries,
that is:
- the map object;
- a 16-slot hash table;
- one node per pair, each with its key, value, hash, and three links: the next node in its
  bucket, and the one before and after it in insertion order.

0282 measured a block that writes one line at 296 bytes, 24 more than before 0282, and named a
smaller map as the place to win them back (0282, open question 2).

A block's annotations are few, read in order by each backend, and almost never looked up by
key. `StderrLogger` and `Slf4jLogger` both walk `entries`. A hash table pays for lookups nobody
makes.

## Not doing

- No change to `LogLine.annotations`' type. It stays a read-only `Map<String, String>`, and a
  backend can't tell which map it was given.
- No change to how spans are added (`annotations + elapsedAt`). A span's line is rarer, and
  `Map +` on any map still works.
- No public map type. The class is `internal` to `surefold`.

## Shape

Nothing new at a call site. `Annotated.map()` builds a `SmallMap`, an immutable map backed by
one array of entries kept in insertion order:

```kotlin
internal class SmallMap(private val pairs: Array<Map.Entry<String, String>>) : AbstractMap<String, String>() {
    override val entries: Set<Map.Entry<String, String>> = /* a read-only view over pairs */
    override fun get(key: String): String? = pairs.firstOrNull { it.key == key }?.value
}
```

Building it keeps today's order rules: outer pairs first, and an inner pair with an outer key
takes the outer key's place with the inner value. Above a size limit (open question 1),
`map()` builds a `LinkedHashMap` as today, so a block with many pairs never pays a linear
lookup.

`AbstractMap` gives `equals` and `hashCode` by the `Map` contract, so a `SmallMap` equals any
`Map` with the same pairs. That includes the `mapOf(...)` the tests compare against.

## Why this shape

Each entry is two references and an object header, with no hash table and no links. Iterating
walks an array. A lookup by key is linear, which is cheap at this size and rare in any case.

The alternative is to keep `LinkedHashMap` but size its table for the pairs it will hold. That
saves part of the table and none of the per-node cost. Not recommended.

## Stack

- [x] **`spec-0283-map`**: `SmallMap`, built by `Annotated.map()` up to the size limit.
      Done when: a suite holds `SmallMap` to the `Map` contract against `LinkedHashMap`, built
      from the same pairs (`equals` both ways, `hashCode`, `get`, `containsKey`, iteration
      order, and an inner pair overriding an outer one). `AnnotationsTest`, `Slf4jLoggerSuite`
      and the carried-context suites pass unchanged. `LocalsBenchmark`'s annotated rows are
      recorded before and after.

### Numbers

`LocalsBenchmark`, 2026-11-02, JDK 25.0.5, 4 vCPU cloud container, 1 fork, 3 warmup and 5 measured
iterations of 1 s, `-prof gc`. Before is 0282.

| Benchmark | Before ns | After ns | Before B/op | After B/op |
|---|---|---|---|---|
| `annotatedLogLineUnderABoundLogger` | 145.0 ± 55.4 | 147.5 ± 84.6 | 296 | 240 |
| `manyLinesUnderOneAnnotation` (10 lines) | 670.0 ± 269.6 | 643.4 ± 86.9 | 296 | 240 |
| `annotatedLogLine` | 191.0 ± 28.1 | 178.6 ± 38.4 | 352 | 296 |
| `annotatedWithoutALine` | 35.4 ± 15.6 | 35.2 ± 7.0 | 120 | 120 |
| `logLine` | 61.1 ± 15.6 | 60.4 ± 21.5 | 0 | 0 |

A block whose annotations are read saves 56 bytes. That is 0282's 24 back and 32 more, and below
0280's 272. Times are within noise.

The Problem section overstated the win. `LinkedHashMap` sizes its table to the pairs it is built
from, so two pairs never get a 16-slot table. The saving is the node and map overhead. A first
`SmallMap` that went through an `ArrayList` and a second array saved only 8 bytes. Building
straight into one exactly-sized array is what reaches 56.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.(annotated|manyLines|logLine)"
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0283": each taken as recommended.

1. **What size limit before falling back to `LinkedHashMap`?** Recommended: 8 pairs. A linear
   scan of 8 string keys still beats hashing the key, and a block with more than 8 annotations
   is rare.
2. **Should the `entries` view create its entry objects when the map is built, or each time it
   is read?** Every logged line iterates `entries`. Recommended: when built, so a line with a
   backend allocates nothing to iterate. Creating them on each read would move bytes onto every
   line, the trap 0282 avoided.
3. **Should `AnnotationCarrier.capture` build a `SmallMap` too,** for the carried
   `surefold.annotation.*` pairs? Recommended: no. A carrier writes those pairs straight into
   headers, so the map is short-lived and seldom repeated.
