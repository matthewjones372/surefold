# 0299 — A saga that outlives its node

## Problem

A saga that spans entities, such as surefold-bank's transfer, has to survive the node it runs on dying between two
steps. Today that means writing it by hand as a persistent sharded entity: `Transfer.kt` is about 130 lines of
phases, events, a codec and a `when` over every phase × answer, for two steps and no undo, and `Account.kt`
keeps its own maps of transfers seen so a repeat is answered the same way. Each new saga repeats
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
import dev.surefold.actor.keyed
import dev.surefold.cluster.Cluster
import dev.surefold.cluster.saga.durableSaga
import dev.surefold.cluster.saga.entityStep

data class Move(val from: String, val to: String, val pence: Long)

/** An account remembers each key's answer in the same append as its events, and answers "did you apply key k?". */
fun account(id: String) = keyed(PersistenceId("account", id), empty = 0L, codec = AccountEvents,
    decide = { balance: Long, command: AccountMsg -> when (command) {
        is Debit -> if (balance < command.pence) refuse(AccountRefused.Funds) else accept(Debited(command.pence))
        is Credit -> accept(Credited(command.pence))
    } },
    event = { balance, e -> when (e) { is Debited -> balance - e.pence; is Credited -> balance + e.pence } },
)

fun Cluster.transfers(accounts: Sharded<AccountMsg>) =
    durableSaga("transfer", MoveCodec, RefusedCodec, ReceiptCodec) { move ->
        entityStep("debit", accounts, move.from, refused = ::asTransfer,
            undo = { Credit(move.pence) }) { Debit(move.pence) }
        entityStep("credit", accounts, move.to, refused = ::asTransfer,
            undo = { Debit(move.pence) }) { Credit(move.pence) }
        Receipt(move)
    }

fun Cluster.send(id: String, move: Move) = transfers(accounts).on(this).start(id, move)  // idempotent on id
fun Cluster.outcome(id: String) = transfers(accounts).on(this).await(id)                // Either<Refused, Receipt>
```

- **The body is plain blocking code**, the same `step(name, action, check, undo)` as 0298, with a codec for what
  the action returns, and the same rule: only a declared `fail` means a step did not happen. It runs as a
  persistent entity of its own kind, sharded by saga id.
- **`keyed(...)`** (in `surefold-actor`) is a persistent behaviour whose commands each carry a key. It writes the
  key and its answer, accepted or refused, in the same append as the events; a key seen before gets the same answer
  and writes nothing; and it answers `Applied(key)` from its state.
- **`entityStep(name, sharded, id, refused, undo) { command }`** is a `step` whose action sends the command to a
  keyed entity through a durable reliable producer (0085, 0099) and waits for its answer, whose refusal is mapped
  through `refused` into the saga's `E`, and whose `check` is the entity's `Applied(key)`. A step on our own entities
  therefore always settles once the entity is reachable; only a failing undo leaves it `Stuck`.
- **Each step is journalled.** `Begun(step)` before the action, `Ended(step, value)` after it. A recovered saga runs
  the body again from the top, and a step already `Ended` returns its kept value without calling anything. A step
  `Begun` but not `Ended` is unknown: its `check` decides whether it is `Ended` or runs again, and with no
  `check` the saga stops `Stuck` before calling anything.
- **A step's key** is `"$sagaId/$stepName"` (`…/undo` for its undo), the same on every run and every node, for
  the system called to deduplicate on, such as a reliable command's id or a job's dedupe key.
- **`now()` and `uuid()` in the body** are journalled the first time and return the same value on replay, read
  from the `clock` local so a test clock reaches them.
- **Undoing is journalled too** (`Undoing(why)`, `Undone(step)`), so a node that dies mid-undo resumes the undo
  elsewhere, never the forward steps.
- **An unsettled step or an undo that still fails after its policy** leaves the saga `Stuck`, undoing nothing
  further. It stays in the journal, is counted in
  `surefold.saga.stuck`, and is retried by `resume(id)`, from code or an admin page.
- **Nothing unfinished is left asleep.** A `surefold-sagas` singleton follows the saga kinds' journals for sagas
  not yet ended. When a member is `Removed`, and when the cluster starts, it tells each one `Wake`, and the saga's
  replay decides what is next. A `Wake` to a running or ended saga does nothing. This covers the gaps no reliable
  answer is in flight for: after `Started`, between one step's `Ended` and the next command, and after `Undoing`.
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
      Done when: a saga stopped after its first step and started again calls the second step's action only, and
      one stopped mid-step calls that step's `check` before anything else; `now()` and `uuid()` answer the same
      after a restart; and a 0128 unkind run that crashes at every step boundary ends every saga as it would have
      without a crash.
- [ ] **`spec-0299-undo`** — journalled undos, `Stuck`, `resume`, the `stuck` gauge.
      Done when: an undo failing past its policy leaves `Stuck`, and `resume` after the fix ends it `Undone`.
- [ ] **`spec-0299-cluster`** — `on(cluster)`, `start`/`await` from any node, and the `surefold-sagas` waker.
      Done when: a three-node test kills the saga's node in each gap (after `Started`, between steps, after
      `Undoing`) and every saga ends elsewhere as it would have without the crash.
- [ ] **`spec-0299-keyed`** — `keyed` in `surefold-actor`, and `entityStep` over it.
      Done when: a key sent twice is applied once and answered the same both times, and an `entityStep` whose
      answer is dropped settles through `Applied` without a second command.
- [ ] **`spec-0299-bank`** — surefold-bank's transfer on `durableSaga` and its accounts on `keyed`, the bank's
      README and pages updated to match, plus a `docs/cluster.md` section.
      Done when: `SagaSuite` and `CrashSuite` pass with `Transfer.kt` deleted and `Account.kt` on `keyed`.

## Acceptance

```bash
./gradlew :surefold-cluster-saga:test :surefold-bank:test
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Run steps in `parZip` branches in a durable saga?** Answered (maintainer, 2026-11-04): not in this spec.
   Replaying branches needs per-branch journals, and the first users are sequential; a later spec adds them.
2. **A recovered body that calls a different step than the journal says:** stop as `Diverged`, or carry on from the
   first difference? Answered (maintainer, 2026-11-04): stop as `Diverged`, naming the step journalled and the step
   called, counted like `Stuck` and released by `resume`. A step's value and the saga's input and answer are read
   through codecs that may be `versioned` (0091), so a changed *shape* replays; a changed *order of steps* is
   what diverges, and running in-flight sagas on the body they started with is the later versioning spec.
3. **Reading the clock or a random id in the body** gives a different answer on replay. Make them steps
   (`now()` and `uuid()` journalled), or document it? Answered (maintainer, 2026-11-04): journalled helpers, plus
   a test in the `testActors` unkind runtime (0128) that crashes at every step boundary.
4. **Keep `await` for a finished saga forever?** Answered (maintainer, 2026-11-04): until its events are pruned
   (0076), then `Forgotten`, never "not found". A pruned saga keeps a snapshot of its ending, so `start` with its
   id is still refused and an old transfer can't run again. Audit belongs in a read model (0075).
5. **`entityStep` and `keyed` here or in a spec of their own?** Answered (maintainer, 2026-11-04): here, as
   `spec-0299-keyed`, because the bank rewrite that proves this spec uses them for both steps.
6. **How long does a `keyed` entity remember a key?** Answered (maintainer, 2026-11-04): a window per kind,
   30 days by default, dropped at a snapshot (0076). `resume` refuses a saga stuck for longer than the window of
   any kind it sends to, so a forgotten key can't be sent again.
7. **Who wakes a saga whose node died while no answer was in flight?** Answered (maintainer, 2026-11-04): a
   `surefold-sagas` singleton, as `surefold-producers` does for outboxes (0099), not the service.
