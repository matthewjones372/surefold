# 0299 — A saga that outlives its node

## Problem

A saga that spans entities, such as surefold-bank's transfer, has to survive the node it runs on dying between two
steps. Today that means writing it by hand as a persistent sharded entity: `Transfer.kt` is about 60 lines of
phases, events, a `when` over every phase × answer, and resends, for two steps and no undo. Each new saga repeats
it, and the hard parts (what to redo after a crash, what to do with a late answer, an undo that keeps failing) are
re-decided every time. The in-memory saga (0298) reads as plain code, but forgets everything when its node goes.

## Not doing

- **Timers, signals or waiting days for a person.** A step is a call that returns; a long wait is a job (0216).
- **Exactly-once steps.** A step can run again after a crash. Each one gets a stable key to make that safe.
- **Changing a running saga's code.** A saga whose steps no longer match its journal stops as `Diverged`
  (open question 2); versioning is a later spec.
- **A second journal.** It uses the cluster's journal, snapshots and pruning as they are.

## Shape

In a new module, `surefold-cluster-saga`, on `surefold-cluster` and nothing else:

```kotlin
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.saga.Durable
import dev.surefold.cluster.saga.durableSaga

data class Move(val from: String, val to: String, val pence: Long)

val transfers: Durable<Move, Refused, Receipt> = durableSaga("transfer", MoveCodec, RefusedCodec, ReceiptCodec) { move ->
    val debit = step("debit", DebitCodec, { key -> accounts.debit(key, move.from, move.pence) ?: fail(Refused.Funds) }) {
        key, taken, _ -> accounts.credit(key, move.from, taken.pence)
    }
    step("credit", CreditCodec, { key -> accounts.credit(key, move.to, debit.pence) }) { key, _, _ ->
        accounts.debit(key, move.to, debit.pence)
    }
    Receipt(debit.id)
}

fun Cluster.send(id: String, move: Move) = transfers.on(this).start(id, move)  // returns once Started is written
fun Cluster.outcome(id: String) = transfers.on(this).await(id)                   // Either<Refused, Receipt>, or NotUndone
```

- **The body is plain blocking code**, the same `step(action, undo)` as 0298, with a name and a codec for what the
  action returns. It runs as a persistent entity of its own kind, sharded by saga id.
- **Each step is journalled.** `Begun(step)` before the action, `Ended(step, value)` after it. A recovered saga runs
  the body again from the top, and a step already `Ended` returns its kept value without calling anything. A step
  `Begun` but not `Ended` runs again.
- **A step's key** is `"$sagaId/$stepName"`, the same on every run, for the action and the undo to hand to the
  system they call, such as a reliable command's id or a job's dedupe key.
- **Undoing is journalled too** (`Undoing(why)`, `Undone(step)`), so a node that dies mid-undo resumes the undo
  elsewhere, never the forward steps.
- **An undo that still fails after its policy** leaves the saga `Stuck`. It stays in the journal, is counted in
  `surefold.saga.stuck`, and is retried by `resume(id)`, from code or an admin page.
- **Answers reach any node.** `start` is idempotent on the id, and `await` asks the entity, so a caller can poll or
  wait from wherever it is.
- **surefold-bank's `Transfer` is rewritten on it**, and `SagaSuite` and `CrashSuite` pass unchanged.

## Why this shape

Replaying the body against its journal keeps the saga as readable as the in-memory one, and only steps need to be
saved. **Alternative:** a declared state machine (states, events, transitions) generated into an entity. It can be
drawn and checked without running it, but it is the shape `Transfer.kt` already has and the one this spec wants to
remove. Recommended: replay, with a check that refuses a journal the body no longer matches. **Alternative home:**
the jobs tables (0216), which need Postgres but no cluster. Recommended: the cluster first, because sagas there
already talk to entities, with jobs left as a later backend.

## Stack

- [ ] **`spec-0299-journal`** — the module, `durableSaga`, steps journalled and replayed, keys, on one node.
      Done when: a saga stopped after its first step and started again calls the second step's action only.
- [ ] **`spec-0299-undo`** — journalled undos, `Stuck`, `resume`, the `stuck` gauge.
      Done when: an undo failing past its policy leaves `Stuck`, and `resume` after the fix ends it `Undone`.
- [ ] **`spec-0299-cluster`** — `on(cluster)`, `start`/`await` from any node, and recovery when a node leaves.
      Done when: a three-node test kills the saga's node between steps and the saga ends `Done` elsewhere.
- [ ] **`spec-0299-bank`** — surefold-bank's transfer on `durableSaga`, plus a `docs/cluster.md` section.
      Done when: `SagaSuite` and `CrashSuite` pass with `Transfer.kt` deleted.

## Acceptance

```bash
./gradlew :surefold-cluster-saga:test :surefold-bank:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Run steps in `parZip` branches in a durable saga?** Recommended: not in this spec. Replaying branches needs
   per-branch journals, and the first users are sequential.
2. **A recovered body that calls a different step than the journal says:** stop as `Diverged`, or carry on from the
   first difference? Recommended: stop, with both names in the error. Carrying on silently skips work.
3. **Reading the clock or a random id in the body** gives a different answer on replay. Make them steps
   (`now()` and `uuid()` journalled), or document it? Recommended: journalled helpers, plus a test in the
   `testActors` unkind runtime (0128) that crashes at every step boundary.
4. **Keep `await` for a finished saga forever?** Recommended: until its events are pruned, then answer `Forgotten`.
