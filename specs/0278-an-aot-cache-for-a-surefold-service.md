# 0278 — An AOT cache for a surefold service

## Problem

A surefold service starts cold. The JVM loads and links the Kotlin standard library, surefold, and
whatever else the graph pulls in, then interprets its code until the JIT catches up. JDK 24 can keep
classes loaded and linked in an AOT cache (JEP 483). JDK 25 builds that cache from a single training
run with `-XX:AOTCacheOutput` (JEP 514), and stores method profiles in it too (JEP 515), so the JIT
starts warm. surefold requires JDK 25, so every user already has this.

Nothing in surefold shows it working on a surefold service, and nothing measures what it saves.
A service that scales on load, or restarts often under an orchestrator, pays the cold start every
time. Today such a service either does nothing or works out the training run on its own.

## Not doing

- No library code. The cache belongs to the service's launcher, as 0276 decided for compact
  headers.
- No `surefold-app-gradle` task that builds a cache for any application (open question 1).
- No GraalVM native image and no CRaC. Both are other ways to start warm, with costs of their own.
- No AOT-compiled code (Leyden's later JEPs). Only what JDK 25 ships.

## Shape

`surefold-bank`, the one example application, gets a training run and a recipe that anyone can copy:

```bash
./gradlew :surefold-bank:aotCache    # installDist, then one training run that writes build/aot/bank.aot
./gradlew :surefold-bank:runCached   # the installed jars with -XX:AOTCache=build/aot/bank.aot -XX:AOTMode=on
```

The training run is `--train`. It starts the three nodes in memory, sends a few hundred transfers
through the HTTP API, closes everything and exits 0. The JVM writes the cache as it exits:

```bash
java -XX:AOTCacheOutput=build/aot/bank.aot -cp 'build/install/surefold-bank/lib/*' \
  io.github.matthewjones372.surefold.bank.MainKt --train
```

The bank logs the time from JVM start to its last node being up, read from
`RuntimeMXBean.uptime`. That number is what the cache is measured against.

`docs/app.md` gains "Starting warm with an AOT cache", covering:
- the two commands;
- the rules: the same JDK build and platform for training and production, and a classpath of JARs
  only, with the production classpath starting with the training one;
- `-XX:AOTMode=on`, so a cache the JVM cannot use fails the start instead of being silently ignored.

## Why this shape

An example plus documentation is the smallest change that proves the recipe on real surefold code,
and it touches no library. The alternative, a Gradle plugin task, would have to guess every
application's training workload and launcher. That is worth doing only once the example shows
the saving is large enough to bother with.

## Stack

- [x] **`spec-0278-train`**: `--train` and the time-to-ready log line in `surefold-bank`.
      Done when: a test runs `main` with `--train` and it returns, with no thread left keeping
      the JVM alive.
- [x] **`spec-0278-cache`**: the `aotCache` and `runCached` tasks, and the numbers in this spec.
      Done when: `runCached` starts with `-XX:AOTMode=on`, and time to ready is recorded with and
      without the cache.
- [x] **`spec-0278-docs`**: the `docs/app.md` section, linked from the README's "Try it".
      Done when: the section's commands match the tasks.

### Numbers (`spec-0278-cache`)

2026-11-02, JDK 25.0.5 (Temurin), G1, 4 vCPU cloud container. `--train` on the installed jars, ten runs
alternating cold and cached (`-XX:AOTCache`, `-XX:AOTMode=on`), medians. The cache is 37 MB.

| | Cold | Cached | Change |
|---|---|---|---|
| Started: nodes and servers made (`started in`) | 204 ms | 150 ms | −26% |
| Ready: the cluster formed (`ready in`) | 4,888 ms | 4,703 ms | within noise |
| 300 transfers and close, after ready | 2,654 ms | 2,268 ms | −15% |
| The whole run | 7,522 ms | 6,987 ms | −7% |

The cache does what it should: the JVM starts about a quarter faster, and the first few hundred
transfers run about 15% faster on the stored profiles. The bank's time to ready is mostly gossip
forming the cluster, which waits on timers, so the cache barely moves it. `started in` was added
to measure the part a cache can change. A service that is ready once its servers listen, with no
cluster to form, would see the startup saving directly. Only G1 was measured (open question 4).

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-bank:aotCache :surefold-bank:runCached
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0278": each taken as recommended.

1. **A `surefold-app-gradle` task, or the example and documentation only?** Recommended: the
   example only for now. Revisit once the numbers are in.
2. **What should training exercise: startup alone, or a workload too?** Recommended: a short
   workload. JEP 515's profiles only help methods the training run actually got warm, and a
   start-and-stop run would warm almost nothing past startup.
3. **Should CI build the cache, so the recipe does not rot?** Recommended: yes, as one test that
   trains, then starts with `-XX:AOTMode=on` and expects exit 0. That costs about 30 s in the
   bank's suite. Alternative: a manual task nobody runs, which will break silently.
4. **Does the cache work with the GC a service picks?** On JDK 25 the cached heap objects have
   depended on the collector. Recommended: measure with the default G1, and note any collector
   that loses the heap part rather than promise it works with all of them.
