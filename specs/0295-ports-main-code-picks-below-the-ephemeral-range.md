# 0295 — Ports main code picks below the ephemeral range

## Problem

Four places in main sources pick a port with `ServerSocket(0)`, close it, and bind
it later. `ServerSocket(0)` answers from the kernel's ephemeral range (32768–60999 on
Linux), the range outgoing connections take their local ports from. So in between,
another connection in the same process or on the machine can be given that port.
Test sources had the same race. It timed out `ProducersSuite` in CI, and
surefold/surefold#1044 moved them to `freePort()` in `surefold-actor-remote`'s test
fixtures (spec 0197). Test fixtures are out of reach from main code.

- `surefold-bank`'s `Train.kt` (spec 0278): three cluster nodes for the AOT training
  run, while its own HTTP client opens connections.
- `surefold-kafka-test`'s `KafkaBroker.twoFreePorts()`: the broker that users' tests run
  on (`docs/kafka.md`). It already retries a `BindException` three times.
- `surefold-actor-benchmarks`'s `freePort()` (`RemoteBenchmark`, `ClusterNodes`).
- `surefold-kafka-benchmarks`'s `startBroker()`.

## Not doing

- A public port-picking API in any module.
- Changing what `KafkaBroker` exposes, or its retry.
- Test sources (done in #1044), and sockets held bound for their whole use.

## Shape

A private copy in each module, the same as `TestPorts.kt`:

```kotlin
// Below the ephemeral range every OS hands outgoing connections from (32768 on Linux, 49152 on macOS and Windows).
private const val LOWEST = 20_000
private const val HIGHEST = 32_000
private val handedOut = ConcurrentHashMap.newKeySet<Int>()

private fun freePort(): Int {
    while (true) {
        val port = Random.nextInt(LOWEST, HIGHEST)
        if (handedOut.add(port) && bindable(port)) return port
    }
}
```

`KafkaBroker` and the Kafka benchmark take two distinct ports from it in place of
the two nested `ServerSocket(0)`s.

## Why this shape

A copy per module needs no new dependency: each module may depend only on Surefold
modules it builds on plus its one library. A public helper in `surefold-actor-remote`
would not reach the two Kafka modules. In `surefold` it would be an odd thing for the
core to export. The cost is four ~15-line copies that can drift apart, and a
`handedOut` set per copy, not per JVM. Recommended: the copy, as chosen in review.

## Stack

- [ ] **`spec-0295-actor-ports`** — `Train.kt` and `surefold-actor-benchmarks` pick ports
      from a private `freePort()`.
      Done when: no `ServerSocket(0)` is left in either module's main sources, and
      `./gradlew :surefold-bank:aotCache` still writes the cache.
- [ ] **`spec-0295-kafka-ports`** — `KafkaBroker` and `surefold-kafka-benchmarks` do the
      same.
      Done when: a `KafkaBrokerSuite` test shows both ports below 32768 and distinct.

## Acceptance

```bash
grep -rn "ServerSocket(0)" --include=*.kt */*/src/main   # nothing
./gradlew :surefold-bank:aotCache                        # --train still writes the cache
./gradlew spotlessApply && ./gradlew build
```

## Open questions

1. **Should `KafkaBroker` change at all?** Its three-attempt retry already covers a
   lost port, and it runs on users' machines. There, 20000–32000 is more likely to
   hold one of their own services than an ephemeral port is to be taken.
   **Answered: yes, and the retry stays.** A bindable check is only true at that
   moment, so the retry still covers a port taken between check and bind.
2. **Do the benchmarks need it?** A JMH run is one process doing one thing, so the
   race is less likely there. **Answered: yes**, so one rule holds across the tree
   and no `ServerSocket(0)` pick is left in main sources.
3. **How is `Train`'s change tested?** No test covers its ports, and no CI job runs
   `:surefold-bank:aotCache`. **Answered: by running that task by hand, plus the
   grep under Acceptance.** No new test inspects a private function.
4. **Spec number:** 0294 is taken by the open surefold/surefold#1040.
   **Answered: 0295 is free** on `main`, re-checked when this was answered.
