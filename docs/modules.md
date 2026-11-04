# Modules

Surefold is split into modules so that a service depends only on what it uses.
Each module brings the Surefold modules it builds on and, at most, the one
external library its purpose needs. A `NoOtherDependenciesTest` in every
module checks its runtime classpath against that list, so an unexpected
transitive dependency fails the build.

## Requirements and coordinates

- **JDK 25 or newer** at runtime. The libraries are compiled for 25; see
  [why](core.md#requirements-and-limitations).
- **Kotlin**: built with Kotlin 2.4.10.
- **Group**: `dev.surefold` (`io.github.matthewjones372` up to 0.17.0). Every
  published module shares one version.

```kotlin
dependencies {
    implementation("dev.surefold:surefold:0.18.0")
    implementation("dev.surefold:surefold-app:0.18.0")
    testImplementation("dev.surefold:surefold-test:0.18.0")
}
```

Releases are tagged `vX.Y.Z` and published to Maven Central. Running the
`release` workflow with a version cuts one: it tags that commit on `main`,
builds, publishes, and pushes the tag once Central has the release. The guides
describe the `main` branch, which can be ahead of the latest release; features
not yet released are marked where they are documented.

**Snapshots.** An untagged commit builds as the next patch version with
`-SNAPSHOT` (after `v0.18.0`, `0.18.1-SNAPSHOT`). Every push to `main` publishes
one to GitHub Packages, at `https://maven.pkg.github.com/surefold/surefold-core`, and
it does not expire. GitHub Packages asks for a token even to read a public
package: put a classic personal access token with only `read:packages` in
`~/.gradle/gradle.properties` as `gpr.user` and `gpr.key`, and add

```kotlin
maven("https://maven.pkg.github.com/surefold/surefold-core") {
    credentials {
        username = providers.gradleProperty("gpr.user").get()
        password = providers.gradleProperty("gpr.key").get()
    }
    content { includeGroup("dev.surefold") }
    mavenContent { snapshotsOnly() }
}
```

to the consuming build's repositories. To try an unpublished change, build it locally:

```bash
./gradlew publishToMavenLocal
```

and add `mavenLocal()` to the consuming build's repositories.

## Choosing modules

Start from the core and add the pieces a service needs:

- **Any service:** `surefold`.
- **Wiring and lifecycle:** `surefold-app`, and `surefold-app-actor` for actors as nodes. Apply the
  Gradle plugin from `surefold-app-gradle` to check graphs at build time.
- **Streams:** `surefold-stream` for the description, and one backend: `surefold-stream-forks`, or
  `surefold-stream-actors` to run it as actors.
- **Actors:** `surefold-actor`. Add `surefold-actor-journal-jdbc` for a durable
  journal, `surefold-actor-projection` for read models, `surefold-actor-remote` for a
  second node, and `surefold-cluster` for membership and sharding.
- **A library outside the JDK** (Pekko, Kafka, SQS, Typesafe Config, OpenTelemetry and the rest): its
  module is in [surefold-integrations](https://github.com/surefold/surefold-integrations).
- **Tests:** `surefold-test` and, for streams, `surefold-stream-test`.

## All modules

"Surefold deps" are the Surefold modules a module brings with it. "Adds" is the
external library it brings beyond those, with the version it is built
against; the library's own transitive dependencies come with it.

### Core

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold` | forks, errors, resources, schedules, policies, context, logs, metrics, `await` on any future ([guide](core.md)) | none | nothing | yes |
| `surefold-replay` | a test's forks in seeded orders: `explore` finds the one that breaks it, `replay` runs it again (spec 0042) | `surefold` | none | yes |
| `surefold-compiler` | a K2 checker: a `Deferred`, `Channel` or `Scope` returned from a scope or kept in a property is an error (spec 0041); applied by `dev.surefold.compiler` | none | the Kotlin compiler, already present | yes |
| `surefold-cache` | `Cache`: one lookup per key, a ttl by result, the last good value while the source fails (spec 0246) | `surefold` | none | yes |
| `surefold-guides` | tests only: the guides' examples compiled against the core (spec 0302) | | | no |
| `surefold-structured` | `parZip`, `parMap`, `raceN`, `timeout` on the JDK's `StructuredTaskScope` (preview in JDK 27) | `surefold` | none | no |
| `surefold-core-benchmarks` | JMH benchmarks of locals, log lines, forks and `transact` ([results](../bench/surefold-core-benchmarks/README.md)) | | JMH | no |

### Applications

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-app` | an application as a checked dependency graph ([guide](app.md)) | `surefold` | none | yes |
| `surefold-app-actor` | a `surefold-actor` scope and actors as nodes ([cookbook](cookbook.md#spawn-a-surefold-actor)) | `surefold-app`, `surefold-actor` | none | yes |
| `surefold-app-gradle` | the Gradle plugins `dev.surefold.wiring` ([guide](app.md#checking-the-graph-in-the-build)) and `dev.surefold.compiler`, which applies `surefold-compiler` | none | Gradle API | yes |
| `surefold-app-compiler` | the K2 compiler checker the plugin applies ([guide](app.md#checking-the-graph-in-the-editor)) | none | the Kotlin compiler, already present | yes |

### Streams

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-stream` | `Stream<E, A>` as a description ([guide](stream.md)) | `surefold` | none | yes |
| `surefold-stream-forks` | the backend that runs a stream as a pull loop on a virtual thread | `surefold-stream` | none | yes |
| `surefold-stream-actors` | the backend that runs a stream as `surefold-actor` actors | `surefold-stream-forks`, `surefold-actor` | none | yes |
| `surefold-stream-test` | `TestStreams`, a backend on a `TestClock` | `surefold-stream-forks` | none | yes |

### Integrations

Pekko, Kafka, SQS and SNS, Typesafe Config and kotlinx configuration, Liquibase, OpenFeature, the remote codecs
(Protobuf, Avro, kotlinx), cluster discovery on Kubernetes and AWS, SLF4J, Micrometer, OpenTelemetry, JDBC tracing
and Arrow, with their benchmarks and guides, moved to [surefold-integrations](https://github.com/surefold/surefold-integrations) (spec 0302). Each wraps a
library outside the JDK and Kotlin. Their coordinates did not change: they are still `dev.surefold:<name>`.

### Actors and clusters

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-actor` | actors, supervision, timers, routing, persistence, actor tests ([guide](actors.md)) | `surefold` | none | yes |
| `surefold-actor-journal-jdbc` | the journal, snapshots and read-model offsets on JDBC, tables as a Postgres changelog ([guide](cluster.md#state-that-survives)) | `surefold-actor` | none (bring your own driver and pool) | yes |
| `surefold-actor-projection` | the journal as a `Stream`, for read models that resume ([guide](cluster.md#state-that-survives)) | `surefold-actor`, `surefold-stream` | none | yes |
| `surefold-actor-remote` | actors across nodes over TCP, optional mutual TLS ([guide](cluster.md#two-nodes)) | `surefold-actor` | none | yes |
| `surefold-cluster` | membership, downing, sharding, singletons, reliable delivery, topics ([guide](cluster.md#a-cluster)) | `surefold-actor-remote` | none | yes |
| `surefold-cluster-saga` | durable sagas: step code journalled and replayed after a crash, as sharded entities (spec 0299) | `surefold-cluster` | none | yes |

### Data

Jobs, files, tenancy and its keys, auth, idempotency and rate limits, Postgres notifications and the caches on them,
per-tenant flags and `surefold-app-flyway` moved to
[surefold-platform](https://github.com/surefold/surefold-platform) (spec 0301), with their guides. Their coordinates
did not change: they are still `dev.surefold:<name>`.

### Testing

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-assert` | checks for tests, failing at the test's own line: `shouldBe`, `shouldBeRight` and `shouldBeLeft` for Surefold's `Either`, the string, collection and map matchers, and `withClue` ([guide](testing.md#assertions)) | `surefold` | `opentest4j` 1.3.0 | yes |
| `surefold-test` | `story` and `eventually` ([guide](testing.md#stories-and-waiting)) | `surefold` | none | yes |
| `surefold-junit` | `Suite` objects whose tests share one `Resource` and record their checks, run by a JUnit Platform engine | `surefold`, `surefold-assert`, `surefold-test` | `junit-platform-engine` 6.1.3 | yes |
| `surefold-junit-testkit` | checks on a suite's run, read from `EngineTestKit`: `shouldHaveSucceeded`, `shouldHaveFailed`, `shouldHaveSkipped`, `failureOf`, `finishOrder` ([guide](testing.md#testing-an-aspect)) | none | `junit-platform-testkit` 6.1.3, `opentest4j` 1.3.0 | yes |

### Example

The example bank, `surefold-bank`, moved to
[surefold-platform](https://github.com/surefold/surefold-platform/tree/main/examples/surefold-bank) (spec 0301).

## Stability

Surefold is experimental and versioned `0.x`. Any release can change or remove
public API, and there is no binary-compatibility check in the build. Pin an
exact version, and expect to update code when you upgrade.

Everything in these guides is implemented and tested, the build enforces 90%
line coverage, and the examples in the guides are compiled by tests. No
production use is known. Maturity is uneven: the core, `surefold-app` and
`surefold-stream` have been in place longest and are used by the related projects
listed in the README; the actor, cluster and Kafka modules are newer, and
outside their own tests are exercised mainly by surefold-platform's example bank and
[surefold-bank](https://github.com/surefold/surefold-bank).

`surefold-structured` wraps an API that is itself experimental: the JDK's
`StructuredTaskScope`, a preview API, and it is not published for that reason.

## Performance

Three JMH suites compare Surefold with Pekko or the Kafka client written by hand. They run Pekko, so they
moved to surefold-integrations with it (spec 0302). Their READMEs give the method and every number; a few
headline results:

- **Streams** ([results](https://github.com/surefold/surefold-integrations/blob/main/bench/surefold-stream-benchmarks/README.md)): a five-stage
  element-at-a-time chain costs about 91 ns per element on Pekko Streams after
  Surefold fuses adjacent stages, against about 150 ns unfused, and about 29 ns on
  Forks. On the actors backend, `mapPar` is about 5.7 times faster than on
  Forks and starting a run about twice as fast, while a plain chain is about a
  quarter slower.
- **Actors** ([results](https://github.com/surefold/surefold-integrations/blob/main/bench/surefold-actor-benchmarks/README.md)): against Pekko
  Typed, a remote ask round trip takes about 199 µs against 547 µs, and a burst
  of remote tells about 2.1 µs per tell against 4.0 µs. Idle actors take about
  56% of Pekko's heap each.
- **Kafka** ([results](https://github.com/surefold/surefold-integrations/blob/main/bench/surefold-kafka-benchmarks/README.md)): zio-kafka's
  comparison of 50k records, against the Java client. surefold on Forks takes 657 ms
  against 646 ms for the same hand-written loop and 557 ms for one that never
  commits; about 500 ms of each is the consumer's own close.

These numbers were measured on JDK 21 on a shared four-vCPU cloud container,
before the libraries moved to JDK 25, and the READMEs note they are noisy. The
cluster benchmarks against Pekko Cluster are written but have not been run.

## Known limitations

- **JDK 25 is required.** Services on JDK 21 cannot use Surefold.
- **No coroutine interop layer.** Surefold functions block; call them from
  coroutine code as you would any blocking call.
- **Cancellation is interruption.** Work that never reaches an interruptible
  call cannot be stopped early.
- **Remoting** is at most once for plain tells, and serialization is yours to
  write or generate. Use reliable delivery for commands that must arrive.
- **The JDBC journal** ships tables for Postgres only, and is tested only on
  Postgres.
- **The compiler plugin** reads a subset of graph shapes and falls back to the
  Gradle task for the rest; see [Applications](app.md#checking-the-graph-in-the-editor).

## The example application

`surefold-bank`, three nodes of a bank on the actor, persistence, remoting and cluster modules, lives in
[surefold-platform](https://github.com/surefold/surefold-platform/tree/main/examples/surefold-bank) now, with its
README and how to run it.

