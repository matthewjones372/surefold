# 0298 — A saga that undoes what it did

## Problem

A request that changes two or three outside systems (debit one account, credit another, reserve stock) has to undo
the ones that worked when a later one fails. Today a service writes this with `resourceScope` and `onRelease`,
checking the `ExitCase` by hand. That nearly works, but a declared `fail(e)` arrives as `ExitCase.Completed`
(`Resource.kt`), so the release can't tell a refusal from success. The undo is also unprotected: it runs on the
thread that was just interrupted, so its first blocking call throws. Worst, nothing separates "the other bank said
no" from "the call timed out". Refunding the debit after a timeout pays twice if the credit in fact landed, and
anyone who can make the credit look failed can repeat it.

## Not doing

- **Surviving the process.** A saga here lives in memory, like a `resourceScope`. Spec 0299 is the durable one.
- **Two-phase commit, locks or isolation.** Another request can see a debit before it is refunded.
- **Making a downstream idempotent.** The saga hands each step a stable key; the system called has to honour it.
- **A public `uninterruptible { }`.** The saga uses an internal one; a public region is its own spec.

## Shape

In `surefold`, beside `Resource.kt`, with no new dependency:

```kotlin
import dev.surefold.Schedule
import dev.surefold.fail.Either
import dev.surefold.fail.attempt
import dev.surefold.fail.fail
import dev.surefold.policy
import dev.surefold.saga
import dev.surefold.saga.Settled

val undoing = policy("transfer.undo") { retry(Schedule.recurs(5)) }

fun transfer(id: String, from: String, to: String, pence: Long): Either<Refused, Receipt> = attempt {
    saga(id, undo = undoing) {
        val debit = step(
            "debit",
            action = { key -> bankA.debit(key, from, pence) ?: fail(Refused.Funds) },
            check = { key -> bankA.find(key)?.let { Settled.Happened(it) } ?: Settled.DidNot },
        ) { key, taken, _ -> bankA.refund(key, taken) }
        step(
            "credit",
            action = { key -> bankB.credit(key, to, pence) ?: fail(Refused.Closed) },
            check = { key -> bankB.find(key)?.let { Settled.Happened(it) } ?: Settled.DidNot },
        ) { key, credited, _ -> bankB.reverse(key, credited) }
        Receipt(debit.id)
    }
}
```

- **A step's key** is `"$id/$name"` for the action and the check, and `"$id/$name/undo"` for the undo, so a
  repeat of any of them can be recognised by the system it reaches.
- **A step ends in one of three ways.** It returns: done, and its undo is recorded. It calls `fail(e)`: it did
  not happen. It throws, times out or is interrupted: **unknown**.
- **An unknown step is settled before anything is undone.** The saga calls its `check` through the `undo` policy
  until it answers. `Happened(value)` counts the step as done, so its own undo runs too. `DidNot` counts it as not
  done. A step with no `check`, or one whose check never answers, leaves the saga **unsettled**: no undo runs at
  all, and the saga throws `NotUndone` naming the step. Refunding A while B may have been credited is never
  automatic.
- **Undos** run newest first, across `parZip`/`parMap` branches in the reverse of the order their actions returned.
  Each is told why, as `Undo<E>`: `Failed(error: E)`, `Threw(failure)` or `Cancelled(interrupt)`.
- **Undos are shielded.** They and the checks run on a fresh virtual thread that the interrupted scope does not
  own, and the saga waits for them, restoring the interrupt afterwards.
- **An undo that still fails** does not stop the others. Once all have run, the saga throws `NotUndone`, an
  undeclared failure listing each step left done or unsettled, with the block's own ending as its cause.
- **Numbers** through `metrics`: `surefold.saga.runs` tagged `saga` and `outcome` (`done`, `undone`, `not-undone`),
  and `surefold.saga.checks` tagged `saga` and `answer` (`happened`, `did-not`, `none`).

## Why this shape

A step pairs its action, check and undo at the call, so code reads in the order it runs. Only a declared `fail`
counts as "did not happen", because only the step's author can know that; everything else is unknown until
the other side says. **Alternative:** retry the action with its key until it answers. That is simpler, but only
safe where every downstream deduplicates on keys, which outside banks often don't. Recommended (maintainer,
2026-11-04): the step declares its `check`. **Alternative for failed undos:** a typed `Left`. That makes "money may
be held" look like an ordinary refusal; recommended is the undeclared `NotUndone`, which alerts and pages.

## Stack

- [ ] **`spec-0298-steps`** — `saga`, named steps and keys, `Undo<E>`, sequential undos newest first, `NotUndone`.
      Done when: a `fail(e)` in the third step runs the first two undos in reverse, each told `Failed(e)`.
- [ ] **`spec-0298-settle`** — unknown steps, `check`, `Settled`, and the unsettled ending.
      Done when: a credit that throws and whose check answers `Happened` runs no refund of the debit until the
      credit's own undo has run; one with no check runs no undo and throws `NotUndone`.
- [ ] **`spec-0298-shielded`** — undos and checks on their own thread, the interrupt restored, `undo` policies.
      Done when: a saga interrupted mid-step runs an undo that blocks for 50 ms to the end.
- [ ] **`spec-0298-forks`** — steps recorded from `parZip`/`parMap` branches, metrics, a `docs/core.md` section.
      Done when: a `replay` exploration over two branches, one failing, never misses or doubles an undo.

## Acceptance

```bash
./gradlew :surefold:test --tests '*Saga*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `resourceScope` also tell a `Failed` apart, rather than adding `saga`?** Recommended: no. Its
   `ExitCase` is public, and a saga needs `E`, which `ExitCase` has no type for.
2. **Who settles a step whose outcome is unknown?** Answered (maintainer, 2026-11-04): the step declares a
   `check`; without one the saga is unsettled and undoes nothing.
3. **Default `undo` policy:** none, or a few retries with backoff? Recommended: none, so a saga with no policy does
   exactly what its code says.
4. **A deadline for all undos together**, so a hung refund can't hold a request forever? Recommended: yes, through
   the policy's `deadline`, with no separate setting.
