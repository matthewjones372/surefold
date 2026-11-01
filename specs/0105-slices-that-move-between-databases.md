# 0105 — Slices that move between databases

## Problem

`ShardedJournal` (spec 0088) keeps each id's events in one of 1,024 slices, and gives each database a contiguous
range: slice × databases ÷ 1024. The ranges depend on how many databases there are. A journal on two databases
that gains a third moves about a third of all ids to a database that holds none of their events. Those entities
recover empty. So a journal's write capacity is fixed on the day it is created. surefold-bank found this while
working out how it would scale, after its own config had said "append, never reorder", which is not safe either.

## Not doing

- **Changing the slice count (1,024) or the hash.** Both stay fixed forever.
- **Moving an id to a slice of its own choosing.** Slices move; ids stay in their slice.
- **Moving with no pause at all.** A moving range stops taking appends for its final copy, for seconds.

## Shape

**Routing is a table, not a formula.** Each database owns explicit ranges, kept in a `surefold_journal_slices` table
in the first database. Every node reads it at start, and reads its newest version at most once a second after:

```kotlin
val slices = JdbcSliceTable(primary, listOf("db-0", "db-1"))   // the databases the journal was created on
ShardedJournal(databases, slices)
ShardedSnapshots(snapshotStores, slices)
// db-0: 0..511, db-1: 512..1023   — written once, as version 1, by the rule used before
```

**Each row carries its slice.** `surefold_journal.slice` is set on append, and is never null.

**Each database refuses the slices it has given away.** They are listed in its own `surefold_journal_fenced`, and an
append's insert checks the list in the same statement. A refused append throws `SliceElsewhere`; `ShardedJournal`
reads the table again and sends the append to the slice's new owner, or throws `JournalUnavailable` while the slice
is still moving. Callers already retry both, as when a database is down.

**A range moves in four steps,** run by `SliceMover.move`, the `surefold-journal-move` command, or a service's own
admin endpoint:
1. **Copy:** each id's events the target lacks, and its newest snapshot, twice over while appends continue.
2. **Fence:** the range goes into the source's `surefold_journal_fenced`. On Postgres, the mover then waits until
   every transaction running at the fence has ended (spec 0100's horizon), so no append that missed the fence is
   still to commit. On H2 it waits one second.
3. **Catch up and switch:** the tail written since the copy is copied, the target's own fence on the range, if
   any, is lifted, and the next version is written with the range on the target, in one transaction with a row
   in `surefold_journal_moves`. If any step fails before that, the source is unfenced and nothing moved.
4. **Clean up:** `SliceMover.cleanUp` deletes the range's rows and snapshots from the source once the move is an
   hour old and every named read model's offset on the source has passed the range's last row. It records the
   deleted span in `surefold_journal_pruned`, so the feed reads past it. A range that has moved back is kept.

- **Projections:** events copied to the target reappear in the target's feed. Projections must therefore be
  idempotent by `(id, sequence)`, which `runProjecting` states, and surefold-bank's already are. The source's feed
  loses nothing, because rows are deleted only after the grace period.
- Each step is logged with its duration, as `surefold.journal.move <range>: <step> took <duration>`. The mover runs
  outside any flock, so it has no meter registry of its own.

**What the bank's run found.** Two costs showed at 400 transfers a second. The slice table, once its map was a
second old, was read again by every append that asked, one after another on a monitor, which on JDK 21 also pins
each waiting virtual thread's carrier: the pools ran dry and almost every request failed. Now one caller reads
it, behind a lock, while every other goes on with the map it has. And an index on `(slice, kind, id, seq_nr)`, as
wide as the primary key, doubled each append's index writes; the index is on `slice` alone, which a move, being
rare, can afford to use.

## Why this shape

The table keeps every existing journal working: its first rows are exactly the ranges the formula gives today. A
move pauses only its own range, for as long as the last copy takes, which is how Pekko's R2DBC plugin splits its
data partitions, but online rather than offline. The alternative, consistent hashing across databases, still moves
data whenever the set changes, and hides from the operator which slices moved.

## Stack

- [x] **`spec-0105-table`** — `surefold_journal_slices`, routing from it, and today's ranges written on first start.
      Done when: a journal created before this spec starts unchanged, and routes every id where it did before.
- [x] **`spec-0105-move`** — copy, fence, switch and clean up, as a function and a command.
      Done when: under appends from 1,000 entities, a range moves from db-0 to db-2. No append is lost, every
      entity recovers its full history, and appends to other ranges never pause.

- [ ] **`spec-0105-retention-test-routing`** — one fresh post-cleanup journal for the retention test's expected
      source-feed set; keep every id, assertion, other case and the existing one-minute test deadline.

## Acceptance

```bash
./gradlew :surefold-actor:check :surefold-actor-journal-jdbc:check
```

The retention test creates its expected routing view after cleanup, then shares that journal across all 1,000
ids. Each new slice table owns an initially empty cache; constructing one per id repeats routing SQL without
adding an independent assertion. This test-only change does not establish the cause of a prior timeout.

## Settled

1. **Where does the table live?** In the first database. Every node reads the same version, and a move is one
   transaction, not a rollout of new config.
2. **How long a grace period before the source's rows are deleted?** Until every read model named has passed the
   range's last copied row on the source, and at least an hour.
3. **Can a node that has not yet seen a new version append to a moved range?** No. The source refuses it in the
   append's own statement, and the node reads the table again and appends on the target.
