# 0310 — The tests on one carrier

## Problem

Every fork, actor runner, feed and timer here is a virtual thread. CI runs them on the JDK's default scheduler:
one carrier per core, and the runner has four. Several carriers hide whole classes of bug. A thread that spins or
never yields still lets the others run beside it, and an interleaving that needs one thread to wait for another
rarely comes up. Nothing runs the tests with fewer carriers, so nobody finds out.

There is at least one candidate. `Scope.kt:340`, in a fork's `finish`, spins on `Thread.onSpinWait()` with no bound
while another thread is interrupting the fork. If the interrupter is a virtual thread that unmounts partway through
(`Thread.interrupt` on a virtual thread takes a lock), a lone carrier spins forever. Most other spins are bounded:
the runner's 10 µs spin (`Runners.kt:241`), the actor's `lingered` (`Runtime.kt:936`), and the stream code's capped
`Thread.yield` loops.

The same mode can also hide a hang from the tool meant to report it. surefold-junit's `timeout` aspect (0308)
sleeps on a virtual thread. With one carrier, a test that spins without yielding never lets that sleeper run, so
the test runs into CI's 25-minute step limit with no dump.

## Not doing

- **Fixing what the run finds.** Each failure gets a spec of its own, or a line in the stack below once it is
  understood. This spec builds the run and records what it finds.
- **A pinning check (JFR `jdk.VirtualThreadPinned`).** That is a separate spec.
- **Changing the default scheduler anywhere outside tests.**
- **Rewriting the unbounded spin in `Scope.kt`** before the run shows it.

## Shape

**A Gradle property** sets the scheduler for every test JVM:

```kotlin
providers.gradleProperty("vtParallelism").orNull?.let { n ->
    systemProperty("jdk.virtualThreadScheduler.parallelism", n)
    systemProperty("jdk.virtualThreadScheduler.maxPoolSize", n) // question 1
}
```

`./gradlew build -PvtParallelism=1` runs everything on one carrier.

**A watchdog the scheduler cannot starve.** The `timeout` aspect's sleeper runs on a platform thread of its own.
The test still runs on a virtual thread, as it does today. A spinning test then still fails at its timeout with
0308's dump, which names the spinning thread.

**A CI job.** `build.yml` gets a matrix entry with `gradleArgs: "-PvtParallelism=1"`. It runs on pushes to `main`
and on manual runs, not on pull requests (question 2).

## Why this shape

A system property per test JVM covers both Jupiter and surefold-junit tests without touching either runner. One
carrier is the extreme case: two carriers find fewer bugs, and the default finds none. **Alternative:** `explore`
and `replay` from surefold-replay (0042) control interleavings precisely, but only in code a test opts into. This
mode needs no test changes and covers every test. Recommended: this, as a sweep alongside those tools.

## Stack

- [ ] **`spec-0310-watchdog`**: the `timeout` aspect's sleeper and dump run on a platform thread.
      Done when: a sample suite whose test spins in a `while (true) Thread.onSpinWait()` loop, run with
      `-Djdk.virtualThreadScheduler.parallelism=1`, fails at its timeout, and the dump names the spinning thread.
- [ ] **`spec-0310-property`**: `-PvtParallelism`, as in the sketch.
      Done when: a test run with `-PvtParallelism=1` sees `jdk.virtualThreadScheduler.parallelism` set to `1`, and
      a run without it sees the property unset.
- [ ] **`spec-0310-first-run`**: `./gradlew build -PvtParallelism=1` once. Each module that fails or hangs, with
      its dump, goes into this spec under a new heading, "What one carrier found". No fixes in this entry.
      Done when: that list is committed, or it reads "nothing".
- [ ] **`spec-0310-ci`**: the matrix entry, added once the list is empty or each item has a spec of its own.
      Done when: a push to `main` runs the one-carrier build and it passes.

## Acceptance

```bash
./gradlew :surefold-junit:test
./gradlew build -PvtParallelism=1 --continue
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Cap `maxPoolSize` at the same number?** Without the cap, a thread that blocks in a way the JDK compensates for
   (`Object.wait`, some file I/O) gets a temporary extra carrier, which hides exactly the starvation this looks for.
   Answered (maintainer, 2026-11-04), as recommended: cap it. A thread that needs compensation then shows up as a
   hang with a dump, and that is a finding.
2. **Run on pull requests too?** It doubles CI time, and a pull request already builds only what it affects. Answered
   (maintainer, 2026-11-04), as recommended: `main` and manual runs only, so a failure shows up within one merge and
   a pull request can still run it by hand.
3. **One carrier, or two as well?** Two finds the bugs where exactly two threads hand work to each other, and one
   finds more besides. Answered (maintainer, 2026-11-04), as recommended: one alone, and add two only if the first
   run turns up something one carrier cannot.
4. **Should the watchdog move for every run, or only under `-PvtParallelism`?** Answered (maintainer, 2026-11-04), as
   recommended: every run. A watchdog that does not depend on the scheduler it watches is right on any number of
   carriers, and having one code path keeps it simple.
