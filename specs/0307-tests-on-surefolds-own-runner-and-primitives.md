# 0307 — Tests on Surefold's own runner and primitives

## Problem

Spec 0197 moved most tests onto `surefold-junit`'s `Suite` (327 files). Two groups never moved, and neither falls
under 0197's reasons for staying on Jupiter:

- **`surefold-cache`** (13 Jupiter files) arrived with 0246, after 0197, and is not on `surefold-junit`'s
  classpath, so a bug in it cannot break the runner that reports it.
- **The Pekko modules** (`surefold-actor-pekko`, `-stream-pekko`, `-kafka-pekko`) hold their `ActorSystem` in a
  Jupiter `companion object` with `@AfterAll`, as `AGENTS.md` asks, because `Suite` had no equivalent when they
  were written. `Guarded` (0253) now is one.

Separately, about 120 test files drive concurrency by hand with `Thread`, `CountDownLatch` and `Thread.sleep`:
`surefold` 21, `surefold-cluster` 18, `surefold-actor` 13, `surefold-kafka` 11. Where a test is not testing one of
those primitives, the hand-rolled version is longer, waits on real time, and needs timing margins that can flake
on a slow runner (`SagaShieldedTest`'s deadline case sleeps 400 ms against a 600 ms deadline).

## Not doing

- **Moving what 0197 kept on Jupiter**: `surefold`, `surefold-assert`, `surefold-test`, `surefold-junit`'s own
  tests, the JDK-21 build-tool modules, each `NoOtherDependenciesTest`, tests that run the Kotlin compiler, and
  contract fixtures such as `JournalContract`. The runner cannot be the thing it reports on.
- **Changing what any test checks, or its name.**
- **Rewriting a test whose subject is a thread, a latch or real time**, such as interrupt delivery, `joinFully`,
  or a timeout measured against the wall clock. Those keep their threads.
- **Moving the Pekko modules' tests across repositories.** They move with their modules under 0302, unchanged.

## Shape

**Cache on `Suite`.** Each `surefold-cache` test class becomes an `object … : Suite()` with the same test
sentences, as 0197 did for the rest.

**An actor system as a `Guarded`.** One shared fixture per module, acquired on first use and released after the
run's last suite, replacing each class's companion and `@AfterAll`:

```kotlin
object PekkoSystem : Guarded<ActorSystem>(permits = Int.MAX_VALUE) {
    override val resource = resource {
        install({ ActorSystem.create("test", ConfigFactory.parseString(CONFIG)) }) { system, _ -> system.terminate() }
    }
}

object AskSuite : Suite() {
    init {
        test("an ask answers with the reply") {
            PekkoSystem.use { system -> /* … */ }
        }
    }
}
```

`AGENTS.md`'s "An actor system belongs to the test class" section is rewritten to name this fixture: one system
per module run, never shared across modules, never stopped by a test.

**Primitives over threads, where the test is not about them:**

| Hand-rolled | Instead |
|---|---|
| `Thread.sleep(n)` to let time pass | a `TestClock` the code reads through `clock`, advanced by the test |
| `CountDownLatch` to order two threads | `scope { }` with `async`/`await`, or a `Channel` |
| several runs hoping to hit a race | `explore`/`replay` from `surefold-replay` (0042) |
| a `Thread` to run a body elsewhere | `scope { async { } }`, so its failure reaches the test |

## Why this shape

`Guarded` already gives a run-wide resource with a release after the last suite, which is what each Pekko class
rebuilds by hand. **Alternative:** a `Suite` lifecycle hook (`beforeAll`/`afterAll`) per suite. That keeps one
system per class, which is closer to today, but costs a system start per class and adds a second lifecycle to
the runner. Recommended: `Guarded`. For primitives, the rule is the test's subject: a test of `timeout` may sleep,
a test of a cache's expiry should not.

## Stack

- [ ] **`spec-0307-cache`** — `surefold-cache`'s 13 test classes onto `Suite`.
      Done when: `:surefold-cache:test` runs the same test names, all through the surefold engine.
- [ ] **`spec-0307-pekko-system`** — the `Guarded` actor-system fixture in each Pekko module, its classes onto
      `Suite`, and the `AGENTS.md` section rewritten.
      Done when: no `@AfterAll` remains in those modules, and a run starts one system per module.
- [ ] **`spec-0307-clock-core`** — `surefold`'s tests that sleep to let time pass, onto `TestClock`, starting with
      `SagaShieldedTest`'s deadline case.
      Done when: none of them calls `Thread.sleep` to wait, and their run time drops.
- [ ] **`spec-0307-clock-cluster`** — the same for `surefold-cluster` and `surefold-actor`.
      Done when: the same, for those modules.
- [ ] **`spec-0307-no-sleep`** — a detekt rule against `Thread.sleep` in test sources, each file that still needs it
      listed in `detekt.yml` with its reason (question 3).
      Done when: a new test that sleeps fails `./gradlew build` until its file is listed with a reason.

## Acceptance

```bash
./gradlew :surefold-cache:test :surefold-actor-pekko:test :surefold-stream-pekko:test :surefold-kafka-pekko:test
grep -rln "Thread.sleep" core/surefold/src/test   # only tests whose subject is real time
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **`Guarded` permits for an actor system:** unlimited, or one per test so tests never share it at once?
   Answered (maintainer, 2026-11-04), as recommended: unlimited; a Pekko system is built to be shared, and tests already use distinct actor names.
2. **Kafka's 11 files** use real brokers and real time on purpose. Leave them out of the clock entries?
   Answered (maintainer, 2026-11-04), as recommended: yes, except where a sleep only waits for a test's own fork, which `await` replaces.
3. **Ban `Thread.sleep` in tests with a detekt rule**, allowed per file with a reason, as `TooGenericExceptionCaught`
   is? Answered (maintainer, 2026-11-04), as recommended: yes, as the last entry, once the clock entries leave only the files that need it.
