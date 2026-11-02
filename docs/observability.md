# Observability: logs, metrics and traces

Logging and metrics calls are part of the core `surefold` module, so library and
service code can write a log line or count something without taking a logger
or a registry as a dependency. Where the output goes is decided by what is on
the classpath:

| Module | Sends | Adds |
|---|---|---|
| none | log lines to stderr; metrics nowhere | nothing |
| `surefold-slf4j` | log lines to SLF4J, annotations in the MDC | `slf4j-api` |
| `surefold-micrometer` | metrics to a Micrometer `MeterRegistry` | `micrometer-core` |
| `surefold-otel` | OpenTelemetry context across forks, and spans | `opentelemetry-api` |

Each integration registers itself through `ServiceLoader`, so adding the
dependency is the whole setup.

```kotlin
dependencies {
    implementation("dev.surefold:surefold-slf4j:0.17.0")
    implementation("dev.surefold:surefold-micrometer:0.17.0")
    implementation("dev.surefold:surefold-otel:0.17.0")
}
```

The complete examples on this page are compiled by `CoreGuideTest`, apart from
the OpenTelemetry ones.

## Logs

`logDebug`, `logInfo`, `logWarn` and `logError(message, cause)` write to the
`Logger` bound on the current thread. `logAnnotated` binds key-value pairs for
a block, and every line written inside it carries them, including lines
written on forks. `logSpan(name)` adds `<name>_ms`, the time the span had been
running when the line was written.

<!-- observability-logs -->
```kotlin
import dev.surefold.logAnnotated
import dev.surefold.logInfo
import dev.surefold.logSpan
import dev.surefold.parMap

fun register(id: String, items: List<Int>): List<Unit> =
    logAnnotated("correlation_id" to id) {
        logSpan("register") {
            parMap(items) { item -> logInfo("checking $item") }   // each line: correlation_id=… register_ms=…
        }
    }
```

An MDC entry does not survive a fork, because it lives in a `ThreadLocal`.
Annotations do, because they are bound in a [`SurefoldLocal`](core.md#what-a-fork-inherits).

**With `surefold-slf4j`,** each line goes to the SLF4J logger named after the
top-level class that wrote it, so `%logger` names that class and a backend can
set levels per package. A line with no class goes to the logger `surefold`. Bind
`Slf4jLogger("surefold")` to send every line to one logger instead. Annotations
are put in the MDC for the call and restored afterwards, so
`%X{correlation_id}`, a JSON encoder and a log search can read them as fields.
The module compiles against `slf4j-api` 1.7 and works with 2.x.

**Your own logger.** `Logger` is a `fun interface` over `LogLine`, which carries
`level`, `message`, `at`, `cause`, `annotations` and `origin`. Binding one with
`logger.locally(MyLogger()) { … }` takes priority over anything registered on
the classpath, for a whole application or for one block. `logger.unbound()`
answers what the classpath registered, whatever is bound around it.

## Metrics

Meters are looked up by name when they are used, so nothing is declared up
front and no node takes a registry:

<!-- observability-metrics -->
```kotlin
import dev.surefold.counter
import dev.surefold.gauge
import dev.surefold.increment
import dev.surefold.metricTagged
import dev.surefold.timed

class Adoptions(private val waiting: MutableList<String>) {
    fun adopt(name: String): String = timed("petshop.adopt.duration.seconds") {
        counter("petshop.adoptions").increment()
        gauge("petshop.queue.depth").set(waiting.size.toDouble())
        waiting.remove(name)
        name
    }

    fun adoptTortoise(): Unit = metricTagged("species" to "tortoise") {
        counter("petshop.adoptions").increment()
    }
}
```

- `counter`, `gauge` and `histogram` take a name and optional tags.
- `timed` records the block's duration in **seconds**, whether the block
  returns or throws, and returns what the block returned.
- `metricTagged` binds tags for a block, inherited by forks like log
  annotations. It is separate from `logAnnotated` on purpose: annotations are
  often unique per request, and a metric tag with unbounded values creates one
  time series per request. Keep tag values few and known in advance.
- `readings(name)` reads a metric back from whichever backend is bound, for a
  service that wants to show its own numbers.
- With no backend bound, measurements are recorded nowhere, at no cost.

**With `surefold-micrometer`,** measurements go to Micrometer's global registry,
which is where Spring and the OpenTelemetry bridge put theirs. Bind
`MicrometerMetrics(registry)` with `metrics.locally(…) { }` to use another.

### Surefold's own metrics

Surefold's modules record through the same API, under names starting `surefold.`, so
installing `surefold-micrometer` exports them with nothing more to write:

- policies, breakers, bulkheads and rate limiters: see [Policies](core.md#policies);
- `transact` steps: `surefold.transact.runs`, `.retries` and `.attempts`, see
  [Why a step ran again](core.md#why-a-step-ran-again);
- streams: `surefold.stream.elements`, `surefold.stream.busy` and
  `surefold.stream.waiting` per stage, for a run started with
  [`measured`](stream.md#optimisation-rendering-and-profiling);
- Kafka consumers and producers: `surefold.kafka.*`, see
  [Meters](kafka.md#meters);
- actors, remoting, membership, sharding, reliable delivery and topics: see
  [Watching a cluster](cluster.md#watching).

## Traces

`surefold-otel` keeps OpenTelemetry's `Context` in a `SurefoldLocal`, so a span opened
before a `parMap` is the parent of the spans each branch opens. It registers a
`ContextStorageProvider` for the whole JVM, so every library that uses
OpenTelemetry's context, such as an instrumented HTTP client, reads and writes
the same context. That process-wide effect is why it is a separate module.

For automatic JDBC query spans from one pool setup, see the [JDBC tracing guide](jdbc-tracing.md).
That integration is unreleased; its privacy boundary and separate performance gate are described there.

```kotlin
import dev.surefold.logInfo
import dev.surefold.otel.span
import dev.surefold.otel.tracedSpan
import dev.surefold.parMap
import io.opentelemetry.api.trace.Tracer

fun pricing(tracer: Tracer, orders: List<Order>): List<Price> =
    tracer.span("price-all") {                                   // a throw is recorded with ERROR status
        parMap(orders) { order -> tracer.span("price") { price(order) } }
    }

fun registering(tracer: Tracer) =
    tracer.tracedSpan("register") { logInfo("started") }        // the line carries trace_id and span_id
```

`tracedSpan` is `span` that also adds `trace_id` and `span_id` to every log
line written inside it, so a log line names its trace and a trace names its
log lines.

A [`transact`](core.md#why-a-step-ran-again) step that ran more than once adds
a `transact.retried` event to the current span when it commits, with its name,
its `attempts`, a `retries.<cause>` count for each value it lost to, and
`solo` when it [ran alone](core.md#when-a-step-keeps-losing) in the end. A step
that commits on its first run adds nothing.

### Across messages, events and records

Log annotations and, with `surefold-otel`, the trace context travel further than
forks:

- **Actor messages.** A `tell` or `ask` carries the sender's context to the
  step that handles it, on the same node or, through `surefold-actor-remote`, on
  another.
- **Journal events.** Events written to the in-memory or JDBC journal keep the
  context as metadata, available to read models as `Followed.metadata`.
- **Kafka records.** This one is explicit. `surefold-kafka` provides
  `ProducerRecord.carrying(Carriers.capture())` to write the current context
  into a record's headers, and `ConsumerRecord.within { }` to restore it while
  the record is handled. Each
  record operator's body also runs with `kafka.topic`, `kafka.partition` and
  `kafka.offset` annotations.

## Profiling with JFR

Logs, metrics and traces say what a service did. A profile says where its CPU
went. JFR ships with the JDK, and JDK 25 adds two kinds of recording that suit
surefold, whose threads spend most of their time parked.

**CPU-time sampling** ([JEP 509](https://openjdk.org/jeps/509), experimental
and Linux-only) samples each thread by the CPU it used, not by wall-clock
time, so parked virtual threads stop crowding out the threads doing the work:

```bash
java '-XX:StartFlightRecording:filename=app.jfr,settings=profile,jdk.CPUTimeSample#enabled=true,jdk.CPUTimeSample#throttle=10ms' ...
jfr view cpu-time-hot-methods app.jfr
jfr summary app.jfr    # jdk.CPUTimeSamplesLost counts the samples the JVM dropped
```

Quote the lost samples next to any percentage taken from the view: at a 10 ms
throttle, a run of `surefold-bank --train` kept 382 samples and lost 107.

**Method timing** ([JEP 520](https://openjdk.org/jeps/520)) counts and times
named methods with no code change, which answers "how often, and how long?"
about one call a profile only shows a share of:

```bash
java '-XX:StartFlightRecording:filename=app.jfr,method-timing=dev.surefold.TransactKt::transact' ...
jfr view method-timing app.jfr
```

Name a concrete function, by the JVM class it compiles to (`TransactKt` for
the top-level functions in `Transact.kt`). Two kinds of name time nothing:

- **An `inline` function**, such as `parMap` or `logAnnotated`. It is compiled
  into each caller, so no method of that name runs.
- **An interface method**, such as `ActorRef::tell`. The filter does not reach
  the classes that implement it.

The benchmarks record the same way with `-Pjfr`, one file per measured JVM
under their `build/jfr`:

```bash
./gradlew :surefold-core-benchmarks:jmh -PbenchmarkArgs="LocalsBenchmark.annotatedLogLine" -Pjfr
jfr view cpu-time-hot-methods bench/surefold-core-benchmarks/build/jfr/<pid>.jfr
```

JDK 25 also takes samples cooperatively ([JEP 518](https://openjdk.org/jeps/518)),
which removes much of the bias older execution samples had. A profile taken on
an earlier JDK is not a like-for-like baseline.

## In tests

`capturingLogs { logs -> … }` and `capturingMetrics { measured -> … }` bind an
in-memory logger or registry for a block, so a test asserts on values rather
than on a backend. See [Testing](testing.md#logs-and-metrics).

For spans, `surefold-otel-test`'s `Spans` is a tracer that keeps every span it
ends. Hand its `tracer` to the code under test and read `all()` or
`named(name)`:

```kotlin
Spans().use { spans ->
    relay(every, outbox, bus, spans.tracer).start(TestStreams(clock))
    clock.adjust(every)
    spans.named("publish").map { it.kind } shouldBe listOf(SpanKind.PRODUCER)
}
```

It is not `opentelemetry-sdk-testing`'s `InMemorySpanExporter`: that artifact
registers a `ContextStorageProvider` of its own, and with two on the classpath
OpenTelemetry falls back to its default storage, so `surefold-otel`'s would be
off in the very tests that check a trace crosses a fork.
