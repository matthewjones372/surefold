# 0300 — An entity that answers a key once

## Problem

A command sent reliably (0085) can arrive more than once, and the entity has to apply it once and answer every
copy the same way. `delivered(...)` drops a duplicate without a turn, so whatever the first turn would have sent
on is lost if the node crashed between writing the events and sending. surefold-bank works around it twice:
`Repeatable.kt` hands every copy to the behaviour, and `Account.kt` keeps maps of transfers already debited or
credited so it can answer a repeat from its state. Every service that takes payments or bookings writes these maps
again. And nothing can ask an entity "did you already apply this?", which a saga (0299) needs to settle a step
whose answer was lost.

## Not doing

- **Keys across entities.** A key is unique within one entity, not across a kind.
- **Keeping keys forever.** They are kept for a window (open question 1).
- **Changing `persistent` or `delivered`.** `keyed` is a behaviour beside them.
- **Replacing `surefold-idempotency`.** That reserves HTTP requests' keys in a Postgres table across instances;
  `keyed` keeps a command's key inside one entity's journal. Each guide links the other.

## Shape

In `surefold-actor`, with `Keyed.codec` in `surefold-actor-remote`:

```kotlin
import dev.surefold.actor.Keyed
import dev.surefold.actor.PersistenceId
import dev.surefold.actor.every
import dev.surefold.actor.keyed
import kotlin.time.Duration.Companion.days

fun account(id: String) = keyed(
    id = PersistenceId("account", id),
    empty = Ledger(),
    codec = AccountEvents,
    remember = 30.days,
    snapshots = every(100, LedgerCodec),
    decide = { ledger: Ledger, command: AccountCommand ->
        when (command) {
            is AccountCommand.Debit ->
                if (ledger.pence < command.pence) refuse(AccountRefused.Funds)
                else accept(AccountEvent.Debited(command.pence))
            is AccountCommand.Credit -> accept(AccountEvent.Credited(command.pence))
        }
    },
    event = { ledger, e -> ledger.after(e) },
)

val accounts = cluster.sharding("account", Keyed.codec(AccountCommandCodec, AccountRefusedCodec), 5.minutes) {
    id -> account(id)
}
accounts.entity("a").ask(2.seconds) { Keyed.Apply("t-1/debit", AccountCommand.Debit(400), it) }  // Accepted
accounts.entity("a").ask(2.seconds) { Keyed.Applied("t-1/debit", it) }                           // Accepted
```

- **Every command carries a key.** `decide` answers `accept(events…)` or `refuse(reason)`. The key and the answer
  are written in the same append as the events, so "debited" and "remembered" can't come apart.
- **A key seen before gets the same answer**, accepted or refused, and writes nothing.
- **`Applied(key)`** answers `Accepted`, `Refused(reason)` or `Unknown` from the entity's state, without a turn of
  `decide`.
- **Sent reliably,** a `Keyed.Apply` is confirmed after its append, as `delivered` does, and every copy is answered.
- **Keys are kept for `remember`.** Each answer written drops the keys older than that, the same way on every
  replay, so state doesn't grow forever. A snapshot keeps the keys still remembered.
  An `Apply` whose key was dropped is applied again, which is why 0299's `resume` refuses sagas older than it.
- **`Keyed.codec(commandCodec, refusedCodec)`** carries the key, the delivery and the reply across nodes.

## Why this shape

The key lives in the entity's own append, the only place it can be atomic with the change. **Alternative:** a
dedupe table beside the journal. It is shared by every entity, but it is a second write that a crash can split
from the events. Recommended: in the append.

## Stack

- [ ] **`spec-0300-keyed`** — `keyed`, `accept`/`refuse`, same answer for a repeat, `Applied`, in `surefold-actor`.
      Done when: a key sent twice is applied once and both copies get the same answer, and `Applied` answers it.
- [ ] **`spec-0300-window`** — `remember`, keys dropped as answers are written, snapshots, `Keyed.codec`, and a `docs/cluster.md`
      section with a compiled fence.
      Done when: a key older than `remember` is gone once another answer is written, replay forgets the same keys,
      a snapshot keeps them, and `GuideExampleTest` compiles it.

## Acceptance

```bash
./gradlew :surefold-actor:test --tests '*Keyed*'
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **How long does a `keyed` entity remember a key?** Answered (maintainer, 2026-11-04): a window per kind,
   30 days by default, dropped as later answers are written (built that way: dropping only at a snapshot would
   make a replay without one remember more than the live entity did). 0299's `resume` refuses a saga stuck for longer than the
   window of any kind it sends to, so a forgotten key can't be sent again.
2. **Should `Applied` on a passivated entity start it?** Answered (maintainer, 2026-11-04): yes. Starting it is a
   replay from the latest snapshot, and the answer has to be definite for a saga to settle.
