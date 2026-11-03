# 0293 — Less shared state written per message

## Problem

Two hot paths write a cache line another thread is also writing, once per event, where once per batch would do.

**The mailbox's room.** Every tell takes room with a compare-and-set on `Cell.room` (`tryAcquireRoom`), and every
handled message gives it back with a `getAndAdd` on the same field (`releaseRoom(1)` in `handled`). In a one-to-one
stream the sender and the actor's runner trade that line on every message. After 0143,
`TellBenchmark.surefoldOneToOne` went from 61 ± 4 to 74 ± 6 ns; the README's guess is that the consumer now stays
close behind the sender and the two contend on the mailbox, not proven.

**The dry runner's bookkeeping.** Each time a runner runs dry, `Runners.run` increments and decrements the shared
`spinning` counter and `workArrives` adds to the shared `spun` total, which exists only for `RunnersSuite` (0144).
This happens even when 0144 has halved the runner's spin down to nothing, so a paced actor pays three shared writes
per activation for a spin that no longer happens.

## Not doing

- **The work queue.** One `ConcurrentLinkedQueue` for every runner stays; per-runner queues are a spec of their own.
- **The tell side.** `tryAcquireRoom`'s CAS and the mailbox's swap stay one each per tell.
- **The activation's monitor.** The two `synchronized(this)` blocks around `running` stay.
- **Batched behaviours.** `ran` already gives back a run's room in one `releaseRoom(items.size)`.

## Shape

Nothing public changes. Inside `Cell`:

- A plain `held: Int`, read and written only by the thread running the activation, counts room taken back but
  not yet given back. `handled` adds 1 to it instead of calling `releaseRoom(1)`.
- `held` is given back in one `releaseRoom(held)` when any of these is true:
  - it reaches `HOLD` (16);
  - `roomWaiters` is not null, because a sender has parked on a full mailbox at some time;
  - the drain ends: after `drain(throughput)` in `run`, before the `when`, so an idle or yielding actor holds
    nothing;
  - the actor finishes.
- An actor with `capacity < 4 * HOLD` never holds, so a small mailbox stays exact.

Inside `Runners`:

- `spun` becomes a `LongAdder`, still `internal`, still read by `RunnersSuite`.
- A runner whose `spin` is 0 skips the spin entirely: no `nanoTime`, no `spinning` increment or decrement, no
  `spun` add. It goes straight to `parks`.

## Why this shape

Holding room makes the mailbox look fuller than it is by at most `HOLD` messages for at most one batch. A sender
that parks because of it is woken within the batch, since a non-null `roomWaiters` stops the holding. One that
calls `offer`, or tells from inside a turn, can be refused where today it would not be. `HOLD = 16` against the
default capacity of 1,024 makes that a 1.6% margin, and a small mailbox (`capacity = 1` in `RuntimeSuite`) does
not hold at all. The alternative, holding for the whole batch of 64, saves more writes but makes the margin four
times wider. Recommended: 16.

For `spun`, the alternative is a plain per-runner total that a retiring runner folds into a shared one. That is
cheaper, but it is more code to keep a test counter. Recommended: `LongAdder`.

## Stack

- [x] **`spec-0293-room-by-the-batch`** ([#1045](https://github.com/surefold/surefold/pull/1045)) — `Cell` holds back room it takes back, as above; a `RuntimeSuite` test
      that a sender parked on a full mailbox of 1,024 is woken before the batch that filled it ends.
      Done when: `TellBenchmark.surefoldOneToOne` is at or below 65 ns on the 0143 machine, with no other row in
      the 0143 table worse beyond its error, and `./gradlew build` is green.
- [ ] **`spec-0293-quiet-dry-runner`** — `spun` as a `LongAdder`; a runner at zero spin skips the spin's
      bookkeeping; a `RunnersSuite` test that a runner at zero spin adds nothing to `spun`.
      Done when: `askCpu`'s paced row uses less CPU per ask than on main, and `./gradlew build` is green.

## Acceptance

```bash
./gradlew :surefold-actor:test --tests '*RuntimeSuite*' --tests '*RunnersSuite*' --tests '*BatchingSuite*'
./gradlew :surefold-actor-benchmarks:jmh -PbenchmarkArgs=".*\.(Ask|Tell|PingPong|FanOut|Blocking)Benchmark\.surefold.*"
./gradlew :surefold-actor-benchmarks:askCpu
./gradlew build
```

## Open questions

1. **Is a refused `offer` within `HOLD` of full acceptable?** It is a visible change for anyone using `offer`
   as a load signal. Decided: yes, and say so in `offer`'s KDoc.
2. **Is `HOLD` fixed, or a fraction of capacity?** `capacity / 64` is exact for small mailboxes without the
   `4 * HOLD` cut-off, but it is one more division to explain. Decided: fixed 16.
3. **Does `spinning` stay a shared counter?** `submit` reads it on every tell that wakes an actor. A per-carrier
   flag would cut the writes further, but `submit` would have to scan. Decided: keep it, revisit after
   the work-queue spec.
4. **Which machine?** The 0143 numbers came from 8 cores and JDK 25; the older rows came from a noisy 4 vCPU
   container. Decided: the 0143 machine, before and after in the same run, as 0143 did.
