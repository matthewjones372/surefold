# 0298 — A saga that undoes what it did

## Problem

A request that changes two or three outside systems (hold a seat, charge a card, reserve stock) has to undo the
ones that worked when a later one fails. Today a service writes this with `resourceScope` and `onRelease`, checking
the `ExitCase` by hand. That nearly works, but a declared `fail(e)` arrives as `ExitCase.Completed`
(`Resource.kt`), so the release can't tell a typed refusal from success, and a team either throws instead of failing
or threads a flag through. The undo is also unprotected: it runs on the thread that was just interrupted, so its
first blocking call throws, and the hold stays held. And steps run in `parZip` branches can't register their undo
anywhere shared.

## Not doing

- **Surviving the process.** A saga here lives in memory, like a `resourceScope`. A crash between steps leaves what
  was done done. Spec 0299 is the durable one.
- **Two-phase commit, locks or isolation.** Another request can see a held seat before it is released.
- **Undoing a step whose action did not return.** An action that throws or is interrupted is treated as not done;
  making it atomic or idempotent is the caller's job (open question 2).
- **A public `uninterruptible { }`.** The saga uses an internal one; a public region is its own spec.

## Shape

In `surefold`, beside `Resource.kt`, with no new dependency:

```kotlin
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fail
import dev.surefold.parZip
import dev.surefold.policy
import dev.surefold.saga
import dev.surefold.Schedule

sealed interface Refused { data object NoRooms : Refused }

val undoing = policy("booking.undo") { retry(Schedule.recurs(5)) }

fun book(trip: Trip): Either<Refused, Booking> = attempt {
    saga(name = "booking", undo = undoing) {
        val seat = step({ flights.hold(trip) }) { held, _ -> flights.release(held) }
        val (car, room) = parZip(
            { step({ cars.hold(trip) }) { held, _ -> cars.release(held) } },
            { step({ hotels.hold(trip) ?: fail(Refused.NoRooms) }) { held, _ -> hotels.release(held) } },
        ) { c, r -> c to r }
        step({ payments.charge(trip, seat, car, room) }) { charge, why -> payments.refund(charge, note = "$why") }
    }
}
```

- **`step(action, undo)`** runs `action`, then records `undo` against what it returned. When the saga's block
  ends any way but returning, every recorded undo runs, newest first, and the block's own ending carries on:
  `attempt` still answers `Left(NoRooms)`.
- **An undo is told why**, as `Undo<E>`: `Failed(error: E)`, `Threw(failure)` or `Cancelled(interrupt)`. A declared
  failure is `Failed`, never "completed".
- **Steps in forks.** A `step` called in a `parZip` or `parMap` branch records into the same saga. Undos run in the
  reverse of the order their actions returned, across branches.
- **Undos are shielded.** They run on a fresh virtual thread that the interrupted scope does not own, and the saga
  waits for them, restoring the interrupt on its own thread afterwards. Each runs through `undo`, the saga's
  policy, with none by default.
- **An undo that still fails** does not stop the others. Once all have run, the saga throws `NotUndone`, an
  undeclared failure listing each step left done and why, with the block's own ending as its cause.
- **A saga is a value too:** `typealias Saga<E, A> = SagaScope<E>.() -> A`, run with `saga { bind() }`, like
  `Resource`.
- **Numbers** through `metrics`: `surefold.saga.runs` tagged `saga` and `outcome` (`done`, `undone`, `not-undone`),
  and `surefold.saga.undos` tagged `saga` and `outcome`.

## Why this shape

A step pairs its action with its undo at the call, so code reads in the order it runs and an undo can't be
forgotten for a step that worked. **Alternative:** a builder of named steps run later, which is easier to draw
as a graph but no longer plain code, and `parZip` and `if` would need their own versions. Recommended: steps in plain
code. **Alternative for failed undos:** a typed `Left` the caller must handle. That makes "money may be held" look
like an ordinary refusal; recommended is the undeclared `NotUndone`, which alerts and pages.

## Stack

- [ ] **`spec-0298-steps`** — `saga`, `step`, `Undo<E>`, sequential steps, undos newest first, `NotUndone`.
      Done when: a `fail(e)` in the third step runs the first two undos in reverse, each told `Failed(e)`, and
      `attempt` answers `Left(e)`.
- [ ] **`spec-0298-shielded`** — undos on their own thread, the interrupt restored afterwards, and `undo` policies.
      Done when: a saga whose scope is interrupted mid-step runs an undo that blocks for 50 ms to the end.
- [ ] **`spec-0298-forks`** — steps recorded from `parZip`/`parMap` branches.
      Done when: a `replay` exploration over two branches, one failing, never misses or doubles an undo.
- [ ] **`spec-0298-docs`** — the two metrics and a `docs/core.md` section with a compiled `<!-- core-saga -->` fence.
      Done when: `CoreGuideTest` compiles the fence and a `capturingMetrics` run reads `outcome=undone`.

## Acceptance

```bash
./gradlew :surefold:test --tests '*Saga*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `resourceScope` also tell a `Failed` apart, rather than adding `saga`?** Recommended: no. Its
   `ExitCase` is public, and a saga needs `E`, which `ExitCase` has no type for.
2. **A step whose action was interrupted may have half-happened.** Offer `step(action, undo, undoIfUnsure = true)`?
   Recommended: not in this spec. Document that actions should be idempotent, and revisit with 0299's keys.
3. **Default `undo` policy:** none, or a few retries with backoff? Recommended: none, so a saga with no policy does
   exactly what its code says.
4. **A deadline for all undos together**, so a hung refund can't hold a request forever? Recommended: yes, through
   the policy's `deadline`, with no separate setting.
