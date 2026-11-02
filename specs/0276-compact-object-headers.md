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

- [ ] **`spec-0276-property`**: `-PcompactHeaders` on the core, actor and stream
      benchmark tasks.
      Done when: `footprint` prints a different per-actor figure with the property than without.
- [ ] **`spec-0276-numbers`**: `footprint`, `TellBenchmark`, `LocalsBenchmark` and one
      stream benchmark, each with and without the flag, in their READMEs. A README
      paragraph saying what the numbers suggest.
      Done when: the README links to the numbers.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-actor-benchmarks:footprint -PcompactHeaders
```

## Open questions

1. **Recommend the flag in the README, or only report the numbers?** Recommended: report,
   and recommend only if allocation rate and footprint both fall by 10% or more.
2. **Should `surefold-app`'s Gradle plugin set it for an application it wires?**
   Recommended: no. The plugin wires a graph and does not own the launcher.
3. **Which machine produces the numbers?** This container's numbers are too noisy to
   publish (`surefold-actor-benchmarks/README.md`). Recommended: the property lands now,
   and the numbers wait for the maintainer's machine.
