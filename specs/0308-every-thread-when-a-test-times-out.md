# 0308 — Every thread when a test times out

## Problem

A test that times out reports one stack, the test's own. Almost every thread here is a virtual thread: forks, actor
runners, the journal committer, the remote wire. So a hang in `surefold-actor` or `surefold-actor-journal-jdbc`
prints where the test waited, and leaves out the runner that never answered.

Spec 0130 fixed this for `surefold-kafka` alone. `ThreadsOnTimeout` writes
`HotSpotDiagnosticMXBean.dumpThreads`, which includes virtual threads, to stderr before Jupiter interrupts. Its open
question 1 deferred every other module "once it has caught something". Two things are still missing:

- **surefold-junit suites**, which most modules run on (0197), get no dump. Their `timeout` aspect interrupts the
  test through `dev.surefold.timeout`, and the threads it unwinds never reach a report.
- **The Jupiter modules** (`surefold` with 63 files, `surefold-cache`, the Pekko modules, `surefold-junit`'s own
  tests) get JUnit's default dump at most. That dump reads `Thread.getAllStackTraces()`, which leaves out virtual
  threads.

A dump is also hard to read where the hang is. Thread names are empty for the actor runner pool
(`Runners.kt:131`) and its watcher (`:257`), `SingleFlight`'s worker, `Saga`'s shielded block, `Wire`'s read watch,
and the writers in every contract fixture (`JournalContract`, `FeedContract`, `SnapshotContract`,
`GroupCommitSuite`, `JdbcSliceTableSuite`). Those all show as `#4711 "" virtual`.

## Not doing

- **Naming fork threads.** `VirtualThreads` runs every fork and sits on the hot path (0284 measures at that
  level). See question 1.
- **A pinning check, or a run with `jdk.virtualThreadScheduler.parallelism=1`.** These are separate specs once
  dumps exist to explain what they find.
- **Longer timeouts, or a file artifact.** The dump goes to stderr, which is already in the XML report that CI
  uploads, as 0130 chose.
- **Examples and benchmarks.** `surefold-bank`, `bench/*`.

## Shape

**Suites.** `surefold-junit`'s `timeout` aspect races the test against a sleeper that writes the dump *before* it
wins. The test's threads are dumped where they hung, not after the interrupt has unwound them. The failure message
stays the same.

```kotlin
fun timeout(duration: Duration): Aspect =
    Aspect(arounds = listOf { _, test ->
        raceN(test) { Thread.sleep(duration.toJavaDuration()); ThreadDump.toStderr("timed out after $duration") }
            .fold({ it }, { throw TimeoutException("no answer within $duration") })
    }, timed = true)
```

**Jupiter.** `ThreadsOnTimeout` and its service file move from `surefold-kafka` into one shared test source. The
root build adds that source to every module's `test` source set and turns autodetection on, limited to our own
extensions:

```kotlin
systemProperty("junit.jupiter.extensions.autodetection.enabled", "true")
systemProperty("junit.jupiter.extensions.autodetection.include", "dev.surefold.*")
```

**Names.** Each unnamed thread that the runtime keeps running is named the way the named ones already are
(`surefold-timers`, `surefold-remote-*`):

| Where | Name |
|---|---|
| `Runners` runner pool | `surefold-actor-runner-N`, from a `factory()` held by `Runners` |
| `Runners` watcher | `surefold-actor-watcher` |
| `SingleFlight` worker | `surefold-single-flight` (not the key: it may hold user data) |
| `Saga.shielded` | `surefold-saga-shielded` |
| `Wire` read watch | `surefold-remote-watch-$peer-$lane` |
| test fixtures | `<suite>-<role>-N`, through `Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name(…, 0).factory())` |

## Why this shape

Dumping from the sleeper, before the race interrupts, is the whole point: a dump taken after `TimeoutException`
shows threads that are already unwinding. **Alternative for Jupiter:** a small published module,
`surefold-junit-jupiter`, that each module depends on. That adds an artifact and a dependency to modules that
`NoOtherDependenciesTest` guards, all for 30 lines of test code. Recommended: the shared source, which is never
published. The dump code itself (`dumpThreads` into a temp directory, then read) is copied once, into
`surefold-junit` and into the shared source, because `surefold`'s own tests cannot depend on `surefold-junit` (0197).

## Stack

- [x] **`spec-0308-suite-dump`** ([#1081](https://github.com/surefold/surefold-core/pull/1081)): `surefold-junit`'s `timeout` aspect dumps every thread before it interrupts.
      Done when: a sample suite whose test parks a named virtual thread and outlives `timeout(1.seconds)` fails with
      the same message as today, and that thread's name and parked frame are in the run's stderr.
- [x] **`spec-0308-jupiter-dump`** ([#1083](https://github.com/surefold/surefold-core/pull/1083)): `ThreadsOnTimeout` moves to the shared test source, registered for every
      module. `surefold-kafka`'s copy and its autodetection line are deleted.
      Done when: `ThreadsOnTimeoutTest` passes in `:surefold` and `:surefold-kafka`, and `surefold-kafka` has no copy.
- [ ] **`spec-0308-names`**: the runtime threads and test fixtures in the table get names.
      Done when: `grep -rn 'Thread.ofVirtual()\.\(start\|unstarted\)' --include=*.kt */*/src/main` lists only
      `VirtualThreads.kt`, examples and benchmarks, and a `RuntimeSuite` test sees a runner named
      `surefold-actor-runner-`.

## Acceptance

```bash
./gradlew :surefold-junit:test :surefold:test :surefold-kafka:test :surefold-actor:test
grep -rn 'Thread.ofVirtual()\.\(start\|unstarted\)' --include=*.kt */*/src/main
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Name fork threads too?** A constant name (`.name("surefold-fork")`, no counter) costs a field write rather
   than a string concatenation. Answered (maintainer, 2026-11-04), as recommended: measure that with
   `surefold-core-benchmarks`' fork benchmark in a follow-up spec, and leave forks unnamed here.
2. **Cap the dump?** A stress suite with 10,000 parked threads writes megabytes into its XML report.
   Answered (maintainer, 2026-11-04), as recommended: no cap, text format; revisit if a
   report gets too big to open.
3. **Autodetection everywhere.** With the `include` pattern, only `dev.surefold.*` extensions load. Any module
   that relies on a third-party extension being autodetected would then lose it. None does today (only
   `surefold-kafka` turns autodetection on). Answered (maintainer, 2026-11-04), as recommended: the pattern as
   above.
4. **JDK 21 build-tool modules.** `dumpThreads` exists on 21, so the shared source compiles there too.
   Answered (maintainer, 2026-11-04), as recommended: register it there too;
   their tests run Gradle builds, where a hang is just as silent.
