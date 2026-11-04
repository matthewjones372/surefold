# 0308 — Runtime code on Surefold's own primitives

## Problem

0307 moves tests off hand-rolled threads, latches and sleeps. Main sources have the same habit, and there it costs
more than flakiness. Reviewing 0299's `spec-0299-cluster` turned up both costs at once:

- **It runs outside the structure.** The saga body ran on `Thread.ofVirtual()`. Nothing owned the thread, so when
  its node's scope closed the body kept running, and the test needed a journal wrapper to stop a dead node writing.
- **It reads the wrong locals.** An actor's turn runs on a runner that inherits none of the scope's locals. `clock`
  and `metrics` read inside a turn are the defaults, not the node's. The saga read them there, and so does
  `keyed`'s answer time (`Keyed.kt:108`), so a `TestClock` bound on the scope never reaches either.

Outside the primitives' own implementations, main sources start threads by hand in `surefold-app-actor`,
`surefold-app-cluster`, `surefold-actor-remote` (watch and unreachable threads), `surefold-actor-journal-jdbc`
(`GroupCommit`, and `SliceMover`'s sleep-poll), `surefold-kafka`'s `Keeper` and `surefold-sqs`'s `Visibility`.
Nothing stops the next one.

## Not doing

- **Code that is the primitive.** `VirtualThreads`, the actor `Runners`, the timer `Wheel`, the transport's
  accept, read and write loops in `Wire.kt`, `surefold-structured`'s `Tasks`, `surefold-replay`'s `Order` and
  `SurefoldEngine` keep their threads. Each is listed in the rule (first entry) with its reason.
- **Tests.** Those are 0307's.
- **Moving modules.** The Kafka and SQS entries land wherever 0302 has put those modules by then (question 3).
- **Changing behaviour a caller can see**, beyond the locals fix in the second entry.

## Shape

The rule is the code's subject, as in 0307: a file that implements a thread, a timer or a transport may use one;
every other file uses the primitive built on it.

| Hand-rolled | Instead |
|---|---|
| `Thread.ofVirtual().start { }` beside a scope | `scope.async { }`, or a child actor's turn when it starts from a turn |
| `while (…) { try …; Thread.sleep(n) }` | `Schedule.spaced(n).retrying { … }` |
| `CountDownLatch` / `CompletableFuture` for one hand-off | `Promise` |
| `AtomicReference` / a locked map for shared state | `Shared`, unless building the value has a side effect |
| a local captured by hand and rebound on another thread | bound for it by the runtime (second entry) |

The rule is detekt's `ForbiddenMethodCall` on `Thread.ofVirtual`, `Thread.ofPlatform`, `Thread(…)` and
`Thread.sleep` in main sources, with per-file excludes in `detekt.yml`, each with its reason, as
`TooGenericExceptionCaught` lists its files.

## Why this shape

A rule first, listing every current use, makes the rest a list that only shrinks, and stops new uses while it
does. For locals, two ways: **(a)** the actor runtime binds the scope's `clock`, `metrics` and `logger` around
every activation, which the `Guardian` already captures; or **(b)** every module captures them where it is built,
as `Scope.cluster` reads its clock today. Recommended: (a). It removes the bug class rather than each instance,
and (b) is what `Keyed.kt` already got wrong. It costs one binding per activation; `LocalsBenchmark` measures it.

## Stack

- [ ] **`spec-0308-rule`** — the detekt rule, every current use listed with its reason.
      Done when: a new `Thread.ofVirtual` in a main source fails `./gradlew build` until its file is listed.
- [ ] **`spec-0308-turn-locals`** — the runtime binds the scope's locals around each activation (question 1).
      Done when: `clock.get()` in a turn reads a `TestClock` bound on the scope, `keyed`'s answer time included.
- [ ] **`spec-0308-actor-remote`** — the remote watch and unreachable threads onto the node's scope.
      Done when: closing a node's scope leaves no `surefold-remote-watch` thread, and the rule lists neither.
- [ ] **`spec-0308-journal-jdbc`** — `GroupCommit`'s thread and `SliceMover`'s sleep-poll onto `Schedule`/forks.
      Done when: the rule lists neither file.
- [ ] **`spec-0308-app`** — `surefold-app-actor`'s holder and `surefold-app-cluster`'s downed thread.
      Done when: the same, for those files.
- [ ] **`spec-0308-integrations`** — `Keeper` and `Visibility` (question 3).
      Done when: the same, for those files.

## Acceptance

```bash
./gradlew detekt
grep -n -A30 "ForbiddenMethodCall" config/detekt/detekt.yml
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Bind locals in the runtime (a), or capture them per module (b)?** Recommended: (a). If a binding per
   activation shows in `LocalsBenchmark`'s ping-pong, bind only when the scope has a binding that differs from the
   default.
2. **Is `AtomicReference` in the rule too?** Recommended: no. It is right in many hot paths, and `Shared` is a
   transaction. Review keeps it to hot paths instead.
3. **Kafka and SQS:** change them here before 0302 moves them, or in `surefold-integrations` after? Recommended:
   after, so the move carries no unreviewed change. The entry is then that repository's to number.
4. **A rule for locals read in a turn,** such as `clock.get()` inside a `behaviour` lambda? Recommended: no, if (a)
   lands. Under (b) it would be needed, and detekt can't see a lambda's thread.
