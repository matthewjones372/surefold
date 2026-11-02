# surefold-core-benchmarks

What `surefold`'s own hot paths cost: a `SurefoldLocal` read and binding, a log line with
annotations, a fork that inherits its opener's bindings, and `transact`. Spec
[0274](../../specs/0274-surefold-local-on-scoped-value.md) asked for it, to compare
`SurefoldLocal` on a `ThreadLocal` with `SurefoldLocal` on a `ScopedValue`.

```bash
./gradlew :surefold-core-benchmarks:jmh                                      # all of it, about 3 minutes
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.read"
```

Results land in `build/jmh-result.json`. Compare numbers only against a baseline taken on
the same machine. Results go in spec 0274.
