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
- **Group**: `io.github.matthewjones372`. Every published module shares one
  version.

```kotlin
dependencies {
    implementation("io.github.matthewjones372:surefold:0.17.0")
    implementation("io.github.matthewjones372:surefold-app:0.17.0")
    testImplementation("io.github.matthewjones372:surefold-test:0.17.0")
}
```

Releases are tagged `vX.Y.Z` and published to Maven Central. The guides
describe the `main` branch, which can be ahead of the latest release; features
not yet released are marked where they are documented.

**Snapshots.** An untagged commit builds as the next patch version with
`-SNAPSHOT` (after `v0.17.0`, `0.17.1-SNAPSHOT`). Snapshots are published by
hand rather than on every push, to
`https://central.sonatype.com/repository/maven-snapshots/`, and expire after
90 days. To try `main` reliably, build it locally:

```bash
./gradlew publishToMavenLocal
```

and add `mavenLocal()` to the consuming build's repositories.

## Choosing modules

Start from the core and add the pieces a service needs:

- **Any service:** `surefold`. Add `surefold-pekko` only to run forks on a Pekko
  dispatcher.
- **Wiring and lifecycle:** `surefold-app`, plus the integrations for what the
  service uses (`surefold-app-typesafe`, `surefold-app-kotlinx`, `surefold-app-liquibase`,
  `surefold-app-pekko`, `surefold-app-actor`, `surefold-app-cluster`). Apply the Gradle plugin from
  `surefold-app-gradle` to check graphs at build time.
- **Streams:** `surefold-stream` for the description, and one backend:
  `surefold-stream-forks` unless you already run Pekko Streams. Kafka adds
  `surefold-kafka`.
- **Actors:** `surefold-actor`. Add `surefold-actor-journal-jdbc` for a durable
  journal, `surefold-actor-projection` for read models, `surefold-actor-remote` and a
  codec module for a second node, and `surefold-cluster` for membership and
  sharding.
- **Observability:** `surefold-slf4j`, `surefold-micrometer`, `surefold-otel`, each only
  if the service uses that backend.
- **Tests:** `surefold-test` and, for streams, `surefold-stream-test`.

## All modules

"Surefold deps" are the Surefold modules a module brings with it. "Adds" is the
external library it brings beyond those, with the version it is built
against; the library's own transitive dependencies come with it.

### Core

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold` | forks, errors, resources, schedules, policies, context, logs, metrics, `await` on any future ([guide](core.md)) | none | nothing | yes |
| `surefold-pekko` | a Pekko dispatcher as the executor; `await` on Pekko's Scala futures ([guide](core.md#running-forks-on-pekko)) | `surefold` | `pekko-actor` 1.2.1 | yes |
| `surefold-replay` | a test's forks in seeded orders: `explore` finds the one that breaks it, `replay` runs it again (spec 0042) | `surefold` | none | yes |
| `surefold-compiler` | a K2 checker: a `Deferred`, `Channel` or `Scope` returned from a scope or kept in a property is an error (spec 0041); applied by `io.github.matthewjones372.surefold.compiler` | none | the Kotlin compiler, already present | yes |
| `surefold-cache` | `Cache`: one lookup per key, a ttl by result, the last good value while the source fails (spec 0246) | `surefold` | none | yes |
| `surefold-structured` | `parZip`, `parMap`, `raceN`, `timeout` on the JDK's `StructuredTaskScope` (preview in JDK 27) | `surefold` | none | no |

### Applications

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-app` | an application as a checked dependency graph ([guide](app.md)) | `surefold` | none | yes |
| `surefold-app-typesafe` | HOCON sections as nodes, every fault at once, where each setting came from ([cookbook](cookbook.md#configuration-a-module-owns)) | `surefold-app` | `com.typesafe:config` 1.4.3 | yes |
| `surefold-app-kotlinx` | a HOCON section read from its `@Serializable` class ([cookbook](cookbook.md#configuration-a-module-owns)) | `surefold-app-typesafe` | `kotlinx-serialization-core` 1.11.0 | yes |
| `surefold-app-liquibase` | a changelog as a node that readers of the database depend on ([cookbook](cookbook.md#run-migrations-as-a-step)) | `surefold-app` | `liquibase-core` 4.32.0 | yes |
| `surefold-app-flyway` | SQL migrations on Flyway as a node that readers of the database depend on, the data modules' first ([guide](flyway.md)) | `surefold-app` | `flyway-core` and `flyway-database-postgresql` 13.9.0 (Community, Apache-2.0) | yes |
| `surefold-app-openfeature` | an OpenFeature provider as a node, keyed by `Client`, in readiness and shut down on release ([guide](flags.md)) | `surefold-app`, `surefold-openfeature` | none | yes |
| `surefold-app-openfeature-flagd` | flags in flagd's schema as a section of the service's HOCON, evaluated in-process ([guide](flags.md#flags-in-the-services-config)) | `surefold-app-openfeature`, `surefold-app-typesafe` | `dev.openfeature.contrib.providers:flagd` 0.15.0, with gRPC and netty | yes |
| `surefold-actor-pekko` | `ask` for a Pekko typed actor on the calling virtual thread, without the wiring graph (spec 0058) | `surefold-pekko` | `pekko-actor-typed` 1.2.1 | yes |
| `surefold-app-pekko` | a Pekko typed actor as a node ([cookbook](cookbook.md#spawn-a-pekko-actor)) | `surefold-app`, `surefold-actor-pekko` | `pekko-actor-typed` 1.2.1 | yes |
| `surefold-app-actor` | a `surefold-actor` scope and actors as nodes ([cookbook](cookbook.md#spawn-a-surefold-actor)) | `surefold-app`, `surefold-actor` | none | yes |
| `surefold-app-cluster` | a cluster membership as a node, joined from HOCON ([guide](cluster.md#joined-from-config)) | `surefold-app-actor`, `surefold-app-typesafe`, `surefold-cluster` | none beyond Typesafe Config | yes |
| `surefold-app-gradle` | the Gradle plugins `io.github.matthewjones372.surefold.wiring` ([guide](app.md#checking-the-graph-in-the-build)) and `io.github.matthewjones372.surefold.compiler`, which applies `surefold-compiler` | none | Gradle API | yes |
| `surefold-app-compiler` | the K2 compiler checker the plugin applies ([guide](app.md#checking-the-graph-in-the-editor)) | none | the Kotlin compiler, already present | yes |

### Streams

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-stream` | `Stream<E, A>` as a description ([guide](stream.md)) | `surefold` | none | yes |
| `surefold-stream-forks` | the backend that runs a stream as a pull loop on a virtual thread | `surefold-stream` | none | yes |
| `surefold-stream-pekko` | the backend on Pekko Streams, and operators that take Pekko's types | `surefold-stream`, `surefold-pekko` | `pekko-stream` 1.2.1 | yes |
| `surefold-stream-actors` | the backend that runs a stream as `surefold-actor` actors | `surefold-stream-forks`, `surefold-actor` | none | yes |
| `surefold-stream-test` | `TestStreams`, a backend on a `TestClock` | `surefold-stream-forks` | none | yes |
| `surefold-stream-parity` | tests only: one suite of pipelines every backend must pass | | | no |
| `surefold-stream-benchmarks` | JMH benchmarks of the backends ([results](../bench/surefold-stream-benchmarks/README.md)) | | JMH | no |

### Kafka

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-kafka` | a topic as a `Stream` on any backend, producers, transactions ([guide](kafka.md)) | `surefold-stream` | `kafka-clients` 4.3.0 | yes |
| `surefold-kafka-pekko` | the same through Pekko's Kafka connector, on Pekko Streams | `surefold-kafka`, `surefold-stream-pekko` | `pekko-connectors-kafka` 1.1.0 | yes |
| `surefold-kafka-test` | a broker in the test JVM for a service's own tests, as a surefold-junit `Resource` or a Jupiter extension ([guide](kafka.md#testing-against-a-broker)) | `surefold-kafka` | `embedded-kafka` 4.3.1 | yes |
| `surefold-kafka-benchmarks` | JMH benchmarks against the Java client ([results](../bench/surefold-kafka-benchmarks/README.md)) | | JMH, embedded-kafka | no |

### Actors and clusters

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-actor` | actors, supervision, timers, routing, persistence, actor tests ([guide](actors.md)) | `surefold` | none | yes |
| `surefold-actor-journal-jdbc` | the journal, snapshots and read-model offsets on JDBC, tables as a Postgres changelog ([guide](cluster.md#state-that-survives)) | `surefold-actor` | none (bring your own driver and pool) | yes |
| `surefold-actor-projection` | the journal as a `Stream`, for read models that resume ([guide](cluster.md#state-that-survives)) | `surefold-actor`, `surefold-stream` | none | yes |
| `surefold-actor-remote` | actors across nodes over TCP, optional mutual TLS ([guide](cluster.md#two-nodes)) | `surefold-actor` | none | yes |
| `surefold-actor-remote-protobuf` | message codecs from Protobuf generated classes | `surefold-actor-remote` | `protobuf-java` 4.36.2 | yes |
| `surefold-actor-remote-avro` | message codecs from Avro specific records | `surefold-actor-remote` | `avro` 1.12.2 | yes |
| `surefold-actor-remote-kotlinx` | message, event and state codecs from `@Serializable` classes | `surefold-actor-remote` | `kotlinx-serialization-protobuf` 1.11.0 | yes |
| `surefold-cluster` | membership, downing, sharding, singletons, reliable delivery, topics ([guide](cluster.md#a-cluster)) | `surefold-actor-remote` | none | yes |
| `surefold-cluster-kubernetes` | seeds from the pods API, and a `Lease` for an even split | `surefold-cluster` | `io.fabric8:kubernetes-client-api` 7.9.0 | yes |
| `surefold-cluster-aws` | seeds from Cloud Map or ECS, and a DynamoDB lease | `surefold-cluster` | AWS SDK 2.55.6 `servicediscovery`, `ecs`, `dynamodb` | yes |
| `surefold-actor-benchmarks` | JMH benchmarks against Pekko ([results](../bench/surefold-actor-benchmarks/README.md)) | | JMH, Pekko | no |

### Data

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-jobs` | durable jobs on Postgres, enqueued in the caller's own transaction ([guide](jobs.md)) | `surefold`, `surefold-postgres-notify` | none (bring your own driver and pool) | yes |
| `surefold-app-jobs` | a job worker as a node, started after the migrations and drained on release ([guide](jobs.md#a-kind-a-handler-and-a-worker)) | `surefold-app`, `surefold-jobs` | none: the service picks its migration tool | yes |
| `surefold-jobs-load` | a Proofload load test: jobs enqueued at a fixed rate, drained by 1, 4 and 16 workers (`./gradlew :surefold-jobs-load:run`) | | Proofload, Testcontainers | no |
| `surefold-files` | files on any S3-compatible store, with typed failures, presigned URLs and an in-memory fake ([guide](files.md)) | `surefold` | AWS SDK 2.55.6 `s3`, on its URL-connection client | yes |
| `surefold-files-test` | MinIO in Testcontainers, a fresh bucket a call, for a service's own tests ([guide](files.md#testing)) | `surefold-files` | Testcontainers 2.0.5 | yes |
| `surefold-tenancy` | a tenant forks and messages carry, and Postgres row-level security set for every transaction ([guide](tenancy.md)) | `surefold` | none (bring your own driver and pool) | yes |
| `surefold-tenancy-test` | a check that a service's tables keep two tenants apart ([guide](tenancy.md#testing-isolation)) | `surefold-tenancy` | none | yes |
| `surefold-tenancy-crypto` | a key per tenant under a root key a KMS holds and no backup does; `InMemoryKms` for tests ([guide](tenancy-crypto.md)) | `surefold-tenancy` | none | yes |
| `surefold-tenancy-crypto-aws` | `AwsKms`: one customer managed key per tenant on AWS KMS, found by alias ([guide](tenancy-crypto.md#aws-kms)) | `surefold-tenancy-crypto` | AWS SDK 2.55.6 `kms`, on its URL-connection client | yes |
| `surefold-jobs-tenancy` | each job's handler runs as the tenant its row names ([guide](tenancy.md#jobs)) | `surefold-jobs`, `surefold-tenancy` | none | yes |
| `surefold-idempotency` | idempotency keys on Postgres, reserved atomically across instances, answers replayed ([guide](http-stores.md)) | `surefold`, `surefold-jobs` | none (bring your own driver and pool) | yes |
| `surefold-ratelimit` | token buckets by key, in memory or on Postgres, shared by every instance ([guide](http-stores.md#rate-limits)) | `surefold` | none (bring your own driver and pool) | yes |
| `surefold-postgres-notify` | Postgres's `LISTEN` as a `Stream` of notifications in commit order, on one connection of its own, and `notify` to send ([guide](postgres-notify.md)) | `surefold-stream` | none (bring your own driver) | yes |
| `surefold-cache-postgres` | a `Cache` told by Postgres `NOTIFY` that a row changed: a payload drops its key, a gap drops every key (spec 0246) | `surefold-cache`, `surefold-postgres-notify` | none | yes |
| `surefold-cache-tenancy` | `TenantCache`: a `Cache` whose every entry is one tenant's, looked up as that tenant (spec 0246) | `surefold-cache`, `surefold-tenancy` | none | yes |
| `surefold-openfeature-postgres` | `PostgresFlags`, an OpenFeature provider of flags per tenant from a Postgres table, read under its row-level security policy, and told of a change by `NOTIFY` (spec 0240) | `surefold-openfeature`, `surefold-tenancy`, `surefold-cache`, `surefold-postgres-notify` | none beyond the OpenFeature SDK | yes |
| `surefold-cache-redis` | `RedisLayer`, a `Cache` layer every instance shares on Redis, and `RedisInvalidations` over pub/sub (spec 0246) | `surefold-cache` | `lettuce-core` 6.5.0 | yes |
| `surefold-auth` | sessions and credentials: Argon2id passwords, rotating sessions, magic links, TOTP, roles ([guide](auth.md)) | `surefold` | `bcprov-jdk18on` 1.85 | yes |
| `surefold-auth-oidc` | sign-in with an OpenID Connect provider: the code flow with PKCE, and the ID token's signature, issuer, audience, expiry and nonce checked ([guide](auth.md#signing-in-elsewhere)) | `surefold-auth` | `oauth2-oidc-sdk` 11.37.2 | yes |
| `surefold-jobs-otel` | a span per run of a job, in its own trace and linked to the span that enqueued it ([guide](jobs.md#traces)) | `surefold-jobs`, `surefold-otel` | none beyond `opentelemetry-api` | yes |

### Observability and testing

| Module | For | Surefold deps | Adds | Published |
|---|---|---|---|---|
| `surefold-slf4j` | log lines through SLF4J, annotations in the MDC ([guide](observability.md#logs)) | `surefold` | `slf4j-api` 1.7.36 | yes |
| `surefold-micrometer` | metrics into a Micrometer registry ([guide](observability.md#metrics)) | `surefold` | `micrometer-core` 1.12.0 | yes |
| `surefold-otel` | OpenTelemetry context across forks, and spans ([guide](observability.md#traces)) | `surefold` | `opentelemetry-api` 1.67.0 (with `context` and `common`; no SDK) | yes |
| `surefold-otel-test` | a tracer that keeps the spans it ends, for a service's own tests ([guide](observability.md#in-tests)) | `surefold-otel` | `opentelemetry-sdk` 1.67.0 | yes |
| `surefold-jdbc-otel` | JDBC query tracing with a privacy boundary before OpenTelemetry observers (spec 0266) | `surefold-otel` | `opentelemetry-jdbc` 2.33.1-alpha (OpenTelemetry instrumentation/semantic-convention family; no SDK, driver or pool) | yes |
| `surefold-openfeature` | OpenFeature's evaluation context across forks and actor messages: `evaluatedFor`, `useSurefold()`, `Flag` ([guide](flags.md)) | `surefold` | `dev.openfeature:sdk` 1.23.0, `slf4j-api` | yes |
| `surefold-arrow` | Surefold's `Either`, `NonEmptyList` and `Fail` to and from Arrow's | `surefold` | `arrow-core` 2.1.2 | yes |
| `surefold-assert` | checks for tests, failing at the test's own line: `shouldBe`, `shouldBeRight` and `shouldBeLeft` for Surefold's `Either`, the string, collection and map matchers, and `withClue` ([guide](testing.md#assertions)) | `surefold` | `opentest4j` 1.3.0 | yes |
| `surefold-test` | `story` and `eventually` ([guide](testing.md#stories-and-waiting)) | `surefold` | none | yes |
| `surefold-junit` | `Suite` objects whose tests share one `Resource` and record their checks, run by a JUnit Platform engine | `surefold`, `surefold-assert`, `surefold-test` | `junit-platform-engine` 6.1.3 | yes |
| `surefold-junit-testkit` | checks on a suite's run, read from `EngineTestKit`: `shouldHaveSucceeded`, `shouldHaveFailed`, `shouldHaveSkipped`, `failureOf`, `finishOrder` ([guide](testing.md#testing-an-aspect)) | none | `junit-platform-testkit` 6.1.3, `opentest4j` 1.3.0 | yes |

### Example

| Module | For | Published |
|---|---|---|
| `surefold-bank` | an example application; see [below](#the-example-application) | no |

## Stability

Surefold is experimental and versioned `0.x`. Any release can change or remove
public API, and there is no binary-compatibility check in the build. Pin an
exact version, and expect to update code when you upgrade.

Everything in these guides is implemented and tested, the build enforces 90%
line coverage, and the examples in the guides are compiled by tests. No
production use is known. Maturity is uneven: the core, `surefold-app` and
`surefold-stream` have been in place longest and are used by the related projects
listed in the README; the actor, cluster and Kafka modules are newer, and
outside their own tests are exercised mainly by `surefold-bank` and
[surefold-bank](https://github.com/matthewjones372/surefold-bank).

Some modules wrap APIs that are themselves experimental:
`surefold-actor-remote-kotlinx` uses kotlinx.serialization's ProtoBuf format, which
kotlinx marks experimental, and `surefold-structured` uses a JDK preview API and is
not published for that reason.

## Performance

Three JMH suites compare Surefold with Pekko or the Kafka client written by hand. Their READMEs give
the method and every number; a few headline results:

- **Streams** ([results](../bench/surefold-stream-benchmarks/README.md)): a five-stage
  element-at-a-time chain costs about 91 ns per element on Pekko Streams after
  Surefold fuses adjacent stages, against about 150 ns unfused, and about 29 ns on
  Forks. On the actors backend, `mapPar` is about 5.7 times faster than on
  Forks and starting a run about twice as fast, while a plain chain is about a
  quarter slower.
- **Actors** ([results](../bench/surefold-actor-benchmarks/README.md)): against Pekko
  Typed, a remote ask round trip takes about 199 µs against 547 µs, and a burst
  of remote tells about 2.1 µs per tell against 4.0 µs. Idle actors take about
  56% of Pekko's heap each.
- **Kafka** ([results](../bench/surefold-kafka-benchmarks/README.md)): zio-kafka's
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
- **Streams:** backends differ in what they run. Pekko-specific operators
  (anything taking a Pekko `Source`, `Flow` or `Sink`) run only on Pekko
  Streams; see [Backends](stream.md#backends).
- **Remoting** is at most once for plain tells, and serialization is yours to
  write or generate. Use reliable delivery for commands that must arrive.
- **The JDBC journal** ships tables for Postgres only, and is tested only on
  Postgres.
- **The compiler plugin** reads a subset of graph shapes and falls back to the
  Gradle task for the rest; see [Applications](app.md#checking-the-graph-in-the-editor).

## The example application

`surefold-bank` is a small bank built on the actor, persistence, remoting and
cluster modules. Accounts and transfers are persistent sharded entities. A
transfer is a saga that debits one account and credits another through durable
reliable sends, so a node that dies in the middle delays a transfer without
losing or doubling money. Each node publishes its numbers to a cluster topic,
which the admin page reads.

```bash
./gradlew :surefold-bank:run
```

That starts three nodes in one JVM with the journal in memory. Each serves a
page for sending money at <http://localhost:8081> to `8083`, and an admin page
at `/admin` that streams the cluster's numbers and has buttons to generate load
and to crash a node.

- `--args="--jdbc URL"` keeps the journal in a Postgres database instead.
- `--args="--node n2 --jdbc URL"` runs a single node in its own process;
  `--node` requires `--jdbc` so the nodes share a journal.

The HTTP server is the JDK's own `HttpServer`, and the pages are plain HTML and
JavaScript, tested in headless Chromium through Playwright. It is not
published and is not a real bank: there is no authentication, and an account
is chosen by its id.
