# surefold-stream-benchmarks

What today's `surefold-stream` costs, measured by JMH. This is the baseline that spec
[0046](../../specs/0046-a-stream-described-once.md) must match and
[0047](../../specs/0047-a-stream-the-compiler-sees-whole.md) must beat.

```bash
./gradlew :surefold-stream-benchmarks:jmh                                    # all of it, about 3 minutes
./gradlew :surefold-stream-benchmarks:jmh -PbenchmarkArgs="ChainBenchmark"   # one class
```

`-PcompactHeaders` runs the measured JVMs with `-XX:+UseCompactObjectHeaders` (JEP 519, spec
[0276](../../specs/0276-compact-object-headers.md)). Without it, the numbers stay comparable with the
baselines here.

Results land in `build/jmh-result.json`. A new baseline is committed to `baseline/`,
named by date and JDK. Compare numbers only against a baseline taken on the same machine.

## Baseline, 2026-09-25

JDK 21.0.10, 4 vCPU Intel Xeon @ 2.10GHz (a shared cloud container), Pekko 1.2.1,
2 forks × 5 iterations of 1 s after 5 of warmup.
The raw results are in [`baseline/2026-09-25-jdk21.json`](baseline/2026-09-25-jdk21.json).

| Row | Per | Time | Allocated |
|---|---|---|---|
| `ChainBenchmark.surefold`: `map`, `map`, `filter`, `map`, `mapOrFail`, `runFold` | element | 149.5 ± 10.9 ns | 84 B |
| `ChainBenchmark.pekko`: the same five stages on Pekko, unguarded | element | 130.2 ± 6.6 ns | 72 B |
| `GroupedWithinBenchmark.surefold`: `groupedWithin(100, 1.seconds)` | element | 114.2 ± 6.9 ns | 19 B |
| `MapParBenchmark.surefold`: `mapPar(8)` with a body that multiplies | element | 8,695 ± 224 ns | 1,082 B |
| `RunManyBenchmark.describedOnce`: 10 elements, `Run` built once | run | 70.5 ± 3.8 µs | 8.5 KB |
| `RunManyBenchmark.describedEachTime`: the same, `Run` built per run | run | 82.5 ± 5.3 µs | 18.0 KB |

What the rows say:

- surefold's guards add about 15% time and 12 B per element on top of hand-written
  Pekko. This is what 0047's merge removes before it removes anything of
  Pekko's.
- `mapPar` forks a virtual thread per element, which costs about 8.7 µs and
  1 KB. It is the right operator for a blocking body and the wrong one for a
  cheap one. Any fork backend (0046) has to beat this number, not the chain's.
- Starting a run costs about 70 µs whatever it carries. Building its stages adds
  another 12 µs and 9.5 KB, which is what 0046's compile-once requirement keeps
  off a pipeline described once.

## Forks beside Pekko, 2026-09-25

The same descriptions on the two backends, after spec 0046's split: the chain and the
per-run rows, 2 forks each. Raw results are in
[`baseline/2026-09-25-jdk21-forks.json`](baseline/2026-09-25-jdk21-forks.json).

| Row | Per | Pekko | Forks |
|---|---|---|---|
| `ChainBenchmark`: the five-stage chain | element | 151.3 ± 9.5 ns, 84 B | 28.9 ± 4.0 ns, 84 B |
| `RunManyBenchmark`: 10 elements, described once | run | 68.6 ± 4.8 µs, 6.5 KB | 30.9 ± 1.3 µs, 0.7 KB |

- On Forks the chain is one pull loop on one thread, with no hand-off between stages,
  which is where Pekko's time per element goes. The 84 B per element that both
  backends allocate is the boxed `Long` each stage answers with, and is the next thing
  a compiled stream (0047) can remove.
- A run on Forks starts one virtual thread and nothing else, where Pekko materialises a
  graph.

## mapPar on Forks, 2026-09-25

After spec 0051's `spec-0051-mappar`: `mapPar(8)` with a body that multiplies, so the row is the
cost of a virtual thread per element and the reordering, not the body. `pekko` is the same written
against Pekko by hand, with no surefold: `mapAsync(8)` and a virtual thread per element.

| Row | Per | Time | Allocated |
|---|---|---|---|
| `MapParBenchmark.forks` | element | 5.8 ± 0.6 µs | 603 B |
| `MapParBenchmark.pekko`, hand-written | element | 6.7 ± 0.2 µs | 715 B |
| `MapParBenchmark.surefold`, on Pekko | element | 8.6 ± 0.3 µs | 1,095 B |

- On Forks the window is refilled on the pulling thread and each body is one virtual thread
  with nothing between it and the loop, where Pekko hands the element to a stage, the stage to
  a future, and the answer back through its own buffer.

## Throughput, 2026-09-25

Elements a second, in JMH's throughput mode (`ThroughputBenchmark.kt`), 2 forks × 5
iterations of 2 s, on the machine above. `pekko` is each pipeline written against Pekko
by hand, with no surefold in it.

| Workload | Forks | surefold on Pekko | Pekko, hand-written |
|---|---|---|---|
| `IngestThroughputBenchmark`: 100,000 CSV lines, parsed, validated, filtered, mapped and batched by 100 | **12.9M/s** ± 1.1M | 7.3M/s ± 0.4M | 5.4M/s ± 0.4M |
| `EnrichThroughputBenchmark`: a 1 ms blocking call per element, 16 in flight | **12,701/s** ± 123 | 11,856/s ± 119 | 12,084/s ± 267 |

- The ingest is CPU-light: the rows are the framework's own cost per element, and on
  Forks a line goes through all five stages as calls on one thread. Allocation is
  about 250 B a line on every row, most of it the line's own `split`.
- The enrich is bounded by the call: sixteen at a time at a millisecond each is 16,000
  a second at best, and the sleep's own overshoot keeps every row near 12,000. There the
  backends are within 5% of each other, which is the number to quote for an I/O-bound
  pipeline.

## Fused, 2026-09-25

After spec 0047's `spec-0047-fuse`: adjacent `map`, `mapOrFail`, `filter` and `filterNot`
compile to one stage. Raw results are in
[`baseline/2026-09-25-jdk21-fused.json`](baseline/2026-09-25-jdk21-fused.json).

| Row | Per | 0046 baseline | Fused |
|---|---|---|---|
| `ChainBenchmark.surefold` | element | 149.5 ± 10.9 ns | 91.1 ± 8.3 ns (−39%) |
| `ChainBenchmark.pekko`, hand-written, unguarded | element | 130.2 ± 6.6 ns | 135.7 ± 6.2 ns |
| `ChainBenchmark.forks` | element | 28.9 ± 4.0 ns | 29.9 ± 2.8 ns |

The described chain on Pekko is now a third faster than the same five stages written
against Pekko by hand, because it is one stage where they are five. On Forks the stages
were already calls in one loop, so fusing changes nothing measurable there.

## Actors beside Forks and Pekko, 2026-09-26

After spec [0066](../../specs/0066-a-stream-on-actors.md): every benchmark gains an `actors` row, run on `Actors` over
a scope held open for the trial, and two benchmarks are new. `ManyRunsBenchmark` starts a thousand ten-element runs
at once and awaits them all; `HandOffBenchmark` is `merge` of two halves and `buffer(64)`, the operators that hand
elements from one thread to another. JDK 21.0.10 on a 4 vCPU Intel Xeon @ 2.80GHz shared cloud container, 2 forks
each. Raw results are in [`baseline/2026-09-26-jdk21-actors.json`](baseline/2026-09-26-jdk21-actors.json).

| Row | Per | Forks | Actors | surefold on Pekko | Pekko, hand-written |
|---|---|---|---|---|---|
| `ChainBenchmark`: five cheap stages | element | **43.1 ± 2.4 ns** | 54.3 ± 3.5 ns | 146.4 ± 12.3 ns | 197.3 ± 16.1 ns |
| `MapParBenchmark`: `mapPar(8)`, a cheap body | element | 7,989 ± 1,094 ns | **1,408 ± 548 ns** | 11,415 ± 811 ns | 9,721 ± 545 ns |
| `RunManyBenchmark`: 10 elements, described once | run | 43.5 ± 2.6 µs | **20.3 ± 0.9 µs** | 90.8 ± 11.0 µs | |
| `ManyRunsBenchmark`: a thousand runs at once | run | **772 ± 137 ns** | 1,227 ± 180 ns | | |
| `HandOffBenchmark`: `buffer(64)` | element | 2,387 ± 222 ns | **978 ± 323 ns** | | |
| `HandOffBenchmark`: `merge` | element | 2,079 ± 759 ns | **1,353 ± 524 ns** | | |
| `GroupedWithinBenchmark`: `groupedWithin(100, 1s)` | element | 25,006 ± 1,768 ns | 23,770 ± 1,203 ns | **184 ± 18 ns** | |
| `IngestThroughputBenchmark`: CSV ingest | element | 7.49M/s ± 0.67M | **7.69M/s** ± 0.34M | 4.22M/s ± 0.35M | 3.25M/s ± 0.18M |
| `EnrichThroughputBenchmark`: a 1 ms call, 16 in flight | element | 12,963/s ± 138 | **13,219/s** ± 166 | 11,957/s ± 195 | 12,152/s ± 210 |

What the rows say:

- **Actors win where an element crosses threads.** `mapPar` is 5.7× faster than on Forks: a tell to a worker that
  already exists, where Forks starts a virtual thread per element. `buffer` and `merge` hand a batch a step to a
  queue the reader takes from, and are 2.4× and 1.5× faster.
- **Starting a run is twice as fast**: spawning an actor on a scope that is already running costs less than
  starting a virtual thread, which is what Forks does per run.
- **Forks keeps the plain chain and many runs at once.** On one thread the chain is calls in a loop on both
  backends; the actor's batch of 64 and its tell to itself cost 11 ns an element. A thousand short runs at once cost
  an actor and a cell each, 1.5 KB against Forks' 0.9 KB, and every one queues for the same four runners.
- **`groupedWithin` was slow on both, and not because of either backend.** Its feed handed each element over on its
  own with two parks and two wakes, 24–25 µs an element against Pekko's 184 ns. Spec
  [0067](../../specs/0067-a-grouped-within-that-keeps-up.md) queues a group at a time on real time, and the `forks` row
  is 317 ± 33 ns against 178 ± 14 ns for the Pekko row, measured together afterwards.
- **Both surefold backends beat Pekko on every row they share with it**, except `groupedWithin`, which is within
  twice Pekko's after 0067.

## The gate

```bash
bench/surefold-stream-benchmarks/gate.sh origin/main                  # every row, the base against this checkout
bench/surefold-stream-benchmarks/gate.sh HEAD "ChainBenchmark"         # uncommitted changes against the last commit
```

`gate.sh` runs the benchmarks on the ref and on this checkout, each from a worktree of its own. A round
runs every benchmark once on each side, one fork each, the two sides of a benchmark back to back and which
goes first alternating (`GATE_ROUNDS`, 3 by default). `jmhCompare` pairs the rounds and fails when a row's
median round is more than 10% slower (`-Ptolerance` to change it) and all rounds but one are more than 5%
slower. Slower is a time per operation that rose, or for a `Throughput` row a score that fell. A row found
slower is measured again on its own for as many rounds more, and fails only if it is still slower over all of
them: one fork far off its others no longer fails the gate. The first table is kept as `rounds/first-compare.txt`.
`gate-tolerance.properties` gives a row the gate failed on identical code a tolerance of its own:
`MapParBenchmark`, `HandOffBenchmark` and `ManyRunsBenchmark.actors` rows may be 50% slower, since their forks
disagree by more than any 10% could hold.

A runner that slows down for a minute slows both sides of the benchmarks it measured in that minute, where
measuring one side's suite and then the other's put the slow minute on one side and read it as a regression.
JMH's error bars are left out of the decision: they measure one run's iterations, not that drift.
The `benchmarks` workflow runs it on a pull request labelled
`benchmarks`, again on each push to it, or from Run workflow, and keeps each run's table as an artifact.

The committed numbers above are records, not thresholds: they were measured on one machine, and a gate
against them on another would be measuring the machine.

## Where the time goes

```bash
./gradlew :surefold-stream-benchmarks:profiles     # build/profiles/<Benchmark>.mmd and .txt
```

Each benchmark's pipeline is run once, measured on Pekko with every element sampled, and drawn
with its profile: each stage's share of the busy time, its busy and waiting per element, and
what it emitted. In the Mermaid, a stage is `hot` from half the time and `warm` from a fifth.
The profiles taken with the fused baseline are in
[`baseline/profiles-2026-09-25`](baseline/profiles-2026-09-25). The `benchmarks` workflow uploads
a fresh set with every gate run.
