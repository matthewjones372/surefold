# 0305 — A region an interrupt waits for

## Problem

Code that has to finish once it has started (a compensation, a refund, a release that tells another system to
let go) runs on whatever thread got there, and that thread has often just been interrupted. Its first blocking
call throws `InterruptedException`, and the undo is left half done. Spec 0298 hits this and plans an internal
shield for saga undos (`spec-0298-shielded`); `resourceScope` releases have the same problem with no plan.
Spec 0271's `uninterruptible` does not cover it: that retries one wait that is safe to repeat, on the same
thread, and not a block of work that must not be cut.

## Not doing

- **Changing 0271's `uninterruptible`.** It keeps its name and meaning. The two are documented side by side.
- **Shielding `resourceScope` releases by default.** That is a change of behaviour with its own spec, if wanted.
- **A time limit inside the region.** Code that must be bounded asks for a `timeout` inside the block.
- **Shielding a computation that never blocks.** Interrupts only land at blocking calls already.

## Shape

In `surefold`, beside `Resource.kt`, with no new dependency:

```kotlin
import dev.surefold.shielded

fun cancelHold(key: String, hold: Hold) = shielded { bank.release(key, hold) }

fun <E> Fail<E>.refund(key: String, taken: Debit): Refund = shielded { bankA.refund(key, taken) ?: fail(Gone) }
```

- `fun <A> shielded(block: () -> A): A` and `fun <E, A> Fail<E>.shielded(block: Fail<E>.() -> A): A`.
- **The block runs on a fork of its own**, which inherits the caller's bindings and clock as any fork does. The
  calling thread joins it and ignores interrupts while it waits.
- **An interrupt that arrives meanwhile is kept, not lost.** When the block ends, the caller's interrupt flag is
  set again, and its next blocking call stops it as it would have.
- **A caller already interrupted on entry** still runs the block to the end: the fork starts with its flag clear.
- **What the block answers is the caller's answer:** its value, its `fail(e)` raised into the caller's `Fail`,
  or its throw rethrown unchanged.
- **A shield inside a shield** waits like any other. An enclosing `timeout` or scope close waits for the region
  too, so it answers late rather than cutting it.

## Why this shape

The JDK cannot mask an interrupt on a thread, so the only way to keep one off a block is to run the block on a
thread nobody else interrupts. That is what 0298's undos need, and it should exist once, publicly, rather than as
a saga internal. **Alternative:** clear the flag on the calling thread and retry each interrupted blocking call.
That is 0271's `uninterruptible`, and it is only safe for waits that can be repeated. **Name:** Ox's
`uninterruptible` is taken by 0271; `shielded` matches the wording of 0298's stack. Recommended: `shielded`.

## Stack

- [ ] **`spec-0305-shielded`** — both `shielded` forms, `ShieldedTest`, a "Work that must finish" section
      beside "Resources" in `docs/core.md`, explaining how it differs from `uninterruptible`.
      Done when: a block that sleeps 50 ms, interrupted 10 ms in, returns its value; the caller's interrupt
      flag is set afterwards; the block's body reads `Thread.currentThread().isVirtual` as true; a `fail(e)` in
      the block is the caller's `Left`.

`spec-0298-shielded` then builds its undos and checks on `shielded` rather than on a private copy.

## Acceptance

```bash
./gradlew :surefold:test --tests '*Shielded*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should 0298's "Not doing" line ("A public `uninterruptible { }` is its own spec") be edited** to point
   here, and its `spec-0298-shielded` entry to depend on this one? Recommended: yes, in the commit that lands
   this spec.
2. **Should `resourceScope`'s `onRelease` run shielded by default?** Recommended: deferred to its own spec.
   Today an interrupted release fails fast, and some callers may rely on that.
3. **Should a shield be visible to `surefold-replay`**, so an exploration can interrupt the caller at every point
   in the region? Recommended: yes, through the same `Waiting` ticket a fork already takes, at no extra cost.
