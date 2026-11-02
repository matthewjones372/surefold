# 0279 — JFR profiles on demand

## Problem

surefold's performance specs quote JFR. 0144 found its runner spin with JFR's execution samples,
0178 and 0186 placed their CPU with JFR profiles. Each was taken by hand, with flags nobody wrote
down, so the next profile starts from scratch and two profiles of the same benchmark cannot be
compared.

JDK 25 adds three things to JFR. Each was checked on this repo's JDK 25.0.5:
- **CPU-time sampling** (JEP 509, experimental, Linux only). `jdk.CPUTimeSample` samples by the
  CPU a thread used, not by wall-clock time. That fits a library whose threads mostly park. On
  `surefold-bank --train` it put `Cell.lingered` and `Fork.finish` near the top of
  `jfr view cpu-time-hot-methods`.
- **Method timing and tracing** (JEP 520). `method-timing=Class::method` counts and times named
  methods with no code change. On the same run it timed `transact` (1,840 calls) and
  `BankNode.transfer` (300).
- **Cooperative sampling** (JEP 518). Samples are taken at safepoints with less bias, which makes
  older JFR profiles in specs less trustworthy than new ones. Nothing to do for this one.

## Not doing

- No surefold JFR events of its own (a fork, an actor turn, a `transact` retry). That would be
  library code and a spec of its own (open question 3).
- No async-profiler, perf or flame-graph tooling. JFR and `jfr view` ship with the JDK.
- No profiling in CI. A profile is something a person reads.

## Shape

The benchmark tasks take `-Pjfr`, the way 0276's `-PcompactHeaders` works, and write one recording
per forked JVM to `build/jfr`:

```bash
./gradlew :surefold-actor-benchmarks:jmh -PbenchmarkArgs="TellBenchmark" -Pjfr
jfr view cpu-time-hot-methods bench/surefold-actor-benchmarks/build/jfr/<pid>.jfr
```

What `-Pjfr` adds to each measured JVM:

```text
-XX:StartFlightRecording:filename=build/jfr/%p.jfr,settings=profile,
    jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=10ms
```

`docs/observability.md` gains "Profiling with JFR", covering the same recording on a service, and
method timing on surefold's public entry points:

```bash
java '-XX:StartFlightRecording:filename=app.jfr,method-timing=io.github.matthewjones372.surefold.TransactKt::transact' ...
jfr view method-timing app.jfr
```

## Why this shape

A Gradle property puts the flags in one place for every benchmark, and the README says what they
are. JMH's own `-prof jfr` was the alternative. It records too, but enabling `jdk.CPUTimeSample`
through it means a `.jfc` settings file of our own to keep up to date, and it covers only JMH
runs, not `footprint` or `askCpu`. A property also matches `-PcompactHeaders`, so the two can be
used together.

## Stack

- [x] **`spec-0279-property`**: `-Pjfr` on the core, stream and actor benchmarks (JMH forks,
      `footprint`, `askCpu`), with `build/jfr` and each README's line.
      Done when: one `LocalsBenchmark` run with `-Pjfr` leaves a recording whose
      `cpu-time-hot-methods` view names surefold code.
- [x] **`spec-0279-docs`**: "Profiling with JFR" in `docs/observability.md`: CPU-time sampling,
      method timing, the views to read them, and the caveats.
      Done when: every command in the section was run on `surefold-bank --train`.

### Found while building it

- `-PcompactHeaders -Pjfr` together reach the measured JVM as one `-jvmArgsAppend`. JMH keeps only one,
  so both modules gather their flags into a single list.
- `LocalsBenchmark.annotatedLogLine` with `-Pjfr`: 455 CPU-time samples kept, 46 lost.
  `HashMap.putMapEntries` takes 21% and `HashMap.resize` 10%. Building the annotation map in
  `logAnnotated` and `logAt` is about half a log line's CPU, more than `Bindings.snapshot` (10.6%),
  which spec 0274 looked at.
- A method-timing filter on the interface method `ActorRef::tell` timed nothing, while `TransactKt::transact`
  was timed in the same run (1,849 calls). This answers open question 4.

## Acceptance

```bash
./gradlew spotlessApply && ./gradlew build
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.bindAndRead" -Pjfr
```

## Open questions

Answered 2026-11-02, when the maintainer said "implement spec 0279": each taken as recommended. On 4,
`ActorRef::tell` was checked and times nothing, so the docs name no interface methods.

1. **CPU-time sampling is experimental and Linux-only. Should `-Pjfr` turn it on everywhere?**
   Recommended: on Linux, add it; elsewhere, record without it and say so. The alternative is
   a second property, which nobody would remember to set.
2. **The trial run lost 107 CPU-time samples against 382 kept, at a 10 ms throttle. Is that
   acceptable?** Recommended: keep 10 ms, and report lost samples next to any percentage quoted
   from the view. Otherwise a spec quoting a profile hides the gap.
3. **Should surefold emit JFR events of its own?** Forks, actor turns and `transact` retries as
   events would show what method timing cannot, such as which fork waited on which.
   Recommended: a separate spec, written once this one shows which question a profile could
   not answer.
4. **Which methods should the docs name for method timing?** An `inline` function such as
   `parMap` is compiled into its caller and cannot be timed by name. Recommended: name only
   public, non-inline entry points such as `transact`, so that renaming an internal never breaks
   the docs. Whether a filter on an interface method, such as `ActorRef.tell`, times its
   implementations is to be checked in `spec-0279-docs` before the docs name one.
