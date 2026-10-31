---
name: surefold
description: Use whenever code depends on surefold (io.github.matthewjones372:surefold* in a build file, or an io.github.matthewjones372.surefold import) before writing a scheduler, a background job, a retry or backoff, a circuit breaker, a test wait, a config read, a secret, a resource's close, a database migration, a SurefoldApp's run, logging, metrics or tracing. surefold already ships each of these; this says which call to use instead of writing it again.
---

# surefold

surefold is structured concurrency, resources, streams, actors and application wiring
on the JDK's virtual threads, with Surefold's own `Fail` and `Either` as the error model.
Blocking is fine everywhere: every fork is a virtual thread, so there is no
`suspend`, no dispatcher and no `CompletableFuture` to reach for.

Before writing any of the code below, use the surefold call beside it.

## Before you write it

| About to write | Use instead |
|---|---|
| `object Main { fun main() … }` wiring things by hand | a `Module` graph and `object App : SurefoldApp<Root>()`, run by `runApp(App)` |
| `run` that calls `server.block()` or `join()` | `awaitShutdown()`; the graph's release stops the server after `run` returns |
| `{ x -> x.close() }` as a node's release | `closingOf(::Thing)` |
| a class that closes what it built | each resource as its own node; the graph releases them in reverse order |
| `Executors.newScheduledThreadPool`, `Timer`, a polling thread | `Stream.tick(every)` started on the graph's `StreamBackend` (`Forks()`) |
| a retry loop, `Thread.sleep` between attempts | `Schedule.exponential(…)` or `Schedule.spaced(…)`, in a `policy { retry(…) }` |
| a down-detector, a failure counter around a client | `policy("…") { guard(CircuitBreaker("…", maxFailures, resetAfter)) }` |
| a stream that dies on one bad dependency call | `restartOnDefect(schedule)` |
| `ConfigFactory.load().getString(…)` in a node | `config<Settings>("section") { Settings(string(…), duration(…)) }` |
| `string("password")` | `secret("password")`, a `Secret` that prints as a mask |
| `if (config.getString("kind") == …)` choosing a module | `choice<Settings, Provided> { chosen -> … }` or `conf.choosing("section", read) { chosen -> … }` |
| `CREATE TABLE IF NOT EXISTS` at start-up | `migrations("db/changelog.sql")` from `surefold-app-liquibase`; readers take a `Migrated` |
| a startup wait for a dependency | `.probe(name, timeout, attempts, interval) { … }` |
| a logger or `MeterRegistry` passed into a class | `logInfo(…)`, `counter(…).increment()`, `timed(…) { … }`; put `surefold-slf4j` and `surefold-micrometer` on the classpath |
| MDC or a trace context carried by hand across threads | `logAnnotated(…) { … }`; `surefold-otel` on the classpath |
| `Thread.sleep` in a test | `eventually(within) { … }` from `surefold-test` |
| a test of something on a timer | `TestStreams` and `TestClock` from `surefold-stream-test` |
| a fake swapped in by hand | `.overriding(single<T> { … })`, and `.subgraph<Root>()` for a smaller graph |
| `runCatching` | catch the exception you expect, by name, or `fail` with a declared failure |

Apply the wiring plugin, `id("io.github.matthewjones372.surefold.wiring")`. It checks
every graph as it compiles and warns on four of these by name:
`SUREFOLD_RUN_NEVER_RETURNS_ON_SHUTDOWN`, `SUREFOLD_SCHEDULER_BY_HAND`,
`SUREFOLD_SECRET_AS_STRING` and `SUREFOLD_SLEEP_IN_TEST`. Fix the code rather than
suppressing one, unless the call really means it.

## Modules

All on Maven Central as `io.github.matthewjones372:<module>`, on one version.

- `surefold`: forks, resources, `Schedule`, `timeout`, `policy`, `Clock`, `TestClock`, logs and metrics, `capturingLogs`, `capturingMetrics`, `logger.unbound()` for what `main` reads.
- `surefold-replay`: `explore` and `replay`, a test's forks in seeded orders.
- `surefold-compiler`: a checker for forks that outlive their scope; plugin `io.github.matthewjones372.surefold.compiler`.
- `surefold-pekko`: a Pekko dispatcher as the executor.
- `surefold-cache`: a cache with one lookup per key and stale values while the source fails.
- `surefold-stream`: `Stream<E, A>`, described once and run on a backend.
- `surefold-stream-forks`: the usual backend, a pull loop on a virtual thread.
- `surefold-stream-pekko`: the Pekko Streams backend.
- `surefold-stream-actors`: the `surefold-actor` backend.
- `surefold-stream-test`: `TestStreams` and `TestClock`.
- `surefold-test`: `story`, `eventually`.
- `surefold-junit`: `Suite` objects run by a JUnit Platform engine.
- `surefold-junit-testkit`: `shouldHaveSucceeded`, `shouldHaveFailed` and `failureOf` on a suite's run.
- `surefold-app`: the graph, `SurefoldApp`, `closingOf`, probes, `testApp`, `Migrated`.
- `surefold-app-typesafe`: `config`, `secret`, `choosing`, `overridingConfig`.
- `surefold-app-kotlinx`: `config<A>(path)` for a `@Serializable` class, no block.
- `surefold-app-liquibase`: `migrations`, which provides `Migrated`.
- `surefold-app-flyway`: `flyway` and `migrate`, Flyway's SQL scripts, which provide `Migrated`; `SurefoldSchema` and `fromLiquibase`.
- `surefold-app-openfeature`: `openFeature { provider }`, a flag provider as a node.
- `surefold-app-openfeature-flagd`: `flagdFlags(path)`, flags in the service's HOCON; `flagdFlags(path, declared)` checks their variants against the declared `Flag`s at start.
- `surefold-actor-pekko`: `ask`, `receive`, `same`, `stopped`, `answer` and `pipeToSelf` for a Pekko typed actor, without the wiring graph.
- `surefold-app-pekko`: a Pekko typed actor as a node.
- `surefold-app-actor`: a `surefold-actor` scope and actors as nodes.
- `surefold-app-cluster`: a cluster membership as a node.
- `surefold-app-gradle`: the wiring plugin, and the plugin that applies `surefold-compiler`.
- `surefold-app-compiler`: the checker behind the wiring plugin.
- `surefold-otel`: traces across forks.
- `surefold-jdbc-otel`: JDBC query tracing with safe automatic names and metadata.
- `surefold-otel-test`: a tracer that keeps the spans it ends, for a service's own tests.
- `surefold-openfeature`: OpenFeature's evaluation context across forks and messages.
- `surefold-arrow`: Surefold's types to and from Arrow's.
- `surefold-assert`: checks for tests, `shouldBe` to `shouldBeRight`, failing at the test's line.
- `surefold-slf4j`: log lines through SLF4J.
- `surefold-micrometer`: metrics on Micrometer.
- `surefold-kafka`: Kafka as a `Stream`, commit after the work, dead letters, transactions.
- `surefold-kafka-pekko`: `surefold-kafka` through Pekko's connector.
- `surefold-kafka-test`: a Kafka broker in the test JVM, for a service's own tests.
- `surefold-actor`: actors as values on virtual threads.
- `surefold-actor-journal-jdbc`: an actor's journal on JDBC.
- `surefold-jobs`: durable jobs on Postgres.
- `surefold-app-jobs`: a job worker as a node.
- `surefold-jobs-otel`: a span per job run.
- `surefold-files`: files on any S3-compatible store.
- `surefold-files-test`: MinIO for a service's own tests.
- `surefold-tenancy`: tenancy on Postgres row-level security.
- `surefold-tenancy-test`: tenant isolation and system role checks for tests.
- `surefold-tenancy-crypto`: a key per tenant, in a KMS.
- `surefold-tenancy-crypto-aws`: those keys on AWS KMS.
- `surefold-jobs-tenancy`: jobs run as their tenant.
- `surefold-idempotency`: idempotency keys on Postgres.
- `surefold-ratelimit`: token-bucket rate limits.
- `surefold-postgres-notify`: Postgres notifications as a stream.
- `surefold-cache-postgres`: a cache invalidated by Postgres notifications.
- `surefold-cache-tenancy`: a cache keyed by the bound tenant.
- `surefold-openfeature-postgres`: flags per tenant from Postgres.
- `surefold-cache-redis`: a shared cache layer on Redis.
- `surefold-auth`: sessions, passwords and second factors.
- `surefold-auth-oidc`: sign-in with an OpenID Connect provider.
- `surefold-actor-projection`: a journal as a resumable `Stream`.
- `surefold-actor-remote`: actors across nodes.
- `surefold-actor-remote-protobuf`: remote messages in Protobuf.
- `surefold-actor-remote-avro`: remote messages in Avro.
- `surefold-actor-remote-kotlinx`: remote messages from `@Serializable` classes.
- `surefold-cluster`: gossip membership and downing.
- `surefold-cluster-kubernetes`: cluster seeds and a Lease on Kubernetes.
- `surefold-cluster-aws`: cluster seeds and a lease on AWS.

## More

`llms.txt` at https://github.com/matthewjones372/lark/blob/main/llms.txt has the
facts, the full table and links to every guide; `docs/cookbook.md` has one
recipe per application task, and `docs/core.md` covers retries, timeouts and
policies.
