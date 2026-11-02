# 0276 — Compact object headers

## Problem

JDK 25 makes compact object headers a product feature (JEP 519): with
`-XX:+UseCompactObjectHeaders` every object header is 8 bytes rather than 12. surefold
allocates many small objects: a fork's state, `Waiter`s, a `SurefoldLocal` frame
(0274), an actor's cell and mailbox nodes, and the persistent maps `logAnnotated` builds.
The flag is off by default. Nothing in the README or the benchmarks says whether a
surefold service should turn it on, and the actor `footprint` numbers (about 520 B per
idle actor) were taken without it.

## Not doing

- Setting the flag on anyone's behalf. surefold is a library, and the flag belongs to
  the service's launcher.
- Any code change to shrink objects. This spec measures and documents.
- AOT caches (JEPs 514 and 515) and the JFR additions. A spec each if wanted.

## Shape

The benchmark tasks take the flag as a property. The README gains a paragraph under
"Try it":

```bash
./gradlew :surefold-actor-benchmarks:footprint -PcompactHeaders
./gradlew :surefold-core-benchmarks:jmh -PcompactHeaders
```

```kotlin
// in each benchmark module's build.gradle.kts, on every JavaExec task
if (providers.gradleProperty("compactHeaders").isPresent) jvmArgs("-XX:+UseCompactObjectHeaders")
```

For `jmh`, the flag goes to the forked JVMs through `-jvmArgsAppend`, not to the
launcher.

## Why this shape

A property keeps the default runs comparable with the baselines already recorded, and
gives each table a second column. The alternative, turning the flag on in every
benchmark, would quietly move every number against its baseline. Not recommended.

## Stack

- [x] **`spec-0276-property`**: `-PcompactHeaders` on the core, actor and stream
      benchmark tasks.
      Done when: `footprint` prints a different per-actor figure with the property than without.
- [ ] **`spec-0276-numbers`**: `footprint`, `TellBenchmark`, `LocalsBenchmark` and one
      stream benchmark, each with and without the flag, in their READMEs. A README
      paragraph saying what the numbers suggest.
      Done when: the README links to the numbers.

### Provisional numbers (`spec-0276-property`)

Taken 2026-11-02 to check that the property works, not for publishing: this container's numbers are
too noisy (open question 3). JDK 25.0.5 (Temurin), 4 vCPU. `LocalsBenchmark` used 1 fork, 3 warmup
and 5 measured iterations of 1 s, with `-prof gc`.

`footprint`, 100,000 idle actors: surefold 742 → **664 B** per actor (−10.5%), Pekko 1,056 → 908 B (−14%).

| `LocalsBenchmark` | Off ns | On ns | Off B/op | On B/op | B/op |
|---|---|---|---|---|---|
| `annotatedLogLine` | 595 ± 977 | 432 ± 496 | 968 | 776 | −20% |
| `forkInheritsABinding` | 45,073 ± 21,176 | 44,200 ± 10,210 | 1394 | 1239 | −11% |
| `forkOnAPool` | 48,054 ± 3,796 | 48,281 ± 4,459 | 854 | 727 | −15% |
| `transactOnce` | 852 ± 181 | 808 ± 313 | 1848 | 1640 | −11% |
| `transactNested` | 1,112 ± 2,705 | 769 ± 284 | 1880 | 1640 | −13% |
| `bindAndRead` | 38.5 ± 19.5 | 36.5 ± 10.2 | 88 | 88 | 0% |
| `readsInsideABinding` | 405 ± 182 | 462 ± 264 | 88 | 88 | 0% |
| `readUnbound` | 3.2 ± 1.8 | 2.7 ± 0.5 | 0 | 0 | — |

Bytes per operation fall 11–20% everywhere one allocates more than a small map, and footprint by
about 10%. That meets open question 1's bar on this machine. Times are within noise. Whether the
README recommends the flag waits for `spec-0276-numbers` on the maintainer's machine.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-actor-benchmarks:footprint -PcompactHeaders
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0276": each taken as recommended.

1. **Recommend the flag in the README, or only report the numbers?** Recommended: report,
   and recommend only if allocation rate and footprint both fall by 10% or more.
2. **Should `surefold-app`'s Gradle plugin set it for an application it wires?**
   Recommended: no. The plugin wires a graph and does not own the launcher.
3. **Which machine produces the numbers?** This container's numbers are too noisy to
   publish (`surefold-actor-benchmarks/README.md`). Recommended: the property lands now,
   and the numbers wait for the maintainer's machine.
