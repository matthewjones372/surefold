# 0266 — JDBC traces without query wrappers

## Problem

Applications need queries under the request/job that caused them, with useful automatic names and no
per-query wrapping. `surefold-otel` carries context across forks; JDBC privacy and runtime cost remain unestablished.

## Not doing

Pool metrics, acquisition/transaction spans, business names, query fingerprints, a SQL parser/JDBC driver,
exporters, sampling policy, a global SDK, trace ids in SQL comments, or tracing connections that bypass the source.
Whole-trace exception/log privacy is separate: an enclosing `Tracer.span` can still record the original JDBC exception.

## Shape

Optional `surefold-jdbc-otel` in `integrations/` composes `surefold-otel` and published
`io.opentelemetry.instrumentation:opentelemetry-jdbc:2.33.1-alpha`; pin the alpha API. Align telemetry API/test SDK
from 1.51.0 to 1.67.0 with compatibility checks. Core and existing data modules gain no dependency. Proposed setup:

```kotlin
val database: DataSource = pool.traced(openTelemetry, identifiers = setOf("bookings", "payments"))
val disabled: DataSource = pool.traced(openTelemetry, enabled = false)
```

`DataSource.traced(OpenTelemetry, enabled: Boolean = true, identifiers: Set<String> = emptySet())` returns a DataSource.
Disabled returns the original instance (`===`) before telemetry access, instrumentation initialization, connections,
parsing or wrapper allocation. Enabled uses public `JdbcTelemetry.wrap`; copy/validate identifiers once at setup.
Identifiers are exact, case-sensitive static schema names matching `[a-z_][a-z0-9_]{0,62}`, at most 256 entries;
reject invalid configuration with a fixed message containing no supplied value. The application owns/closes its pool.

Supported executions create CLIENT spans subject to SDK sampling, inheriting the execution-time parent's trace id
with their own span id; preparation does not capture a parent. No parent means a root; no-op SDK remains valid.
Forks preserve parentage; concurrent requests stay isolated. No per-query annotations, wrappers or stack inspection.
Document startup `-Dotel.semconv-stability.opt-in=database` before instrumentation initialization, affecting JDBC
conventions JVM-wide. Never mutate global settings or inspect upstream internals to establish the effective mode.
Missing, conflicting or already-initialized conventions never refuse database work: validate actual emitted fields.
Accept a stable `db.query.summary` only under the grammar below; otherwise use a validated stable/legacy operation,
then `DB query`. Legacy initial names, database names and connection targets never supply identifiers or fallbacks.

Allowed operations are SELECT, INSERT, UPDATE, DELETE, MERGE, CALL, CREATE, ALTER, DROP, TRUNCATE and BATCH.
A detailed summary is exactly `<operation> <approved identifier>` (single ASCII space); a bare allowed operation is
also valid. Emit this identical safe value as name and `db.query.summary`, maximum 72 ASCII bytes. Reject compound,
quoted, qualified, comma-separated or unknown summaries as a whole; joins/CTEs/multi-query batches fall back to a
validated operation or `DB query`. For operation fallback accept only an exact allowed token from `db.operation.name`,
legacy `db.operation`, or the first ASCII-space-delimited token of a stable summary; never parse query text or names.
Empty identifier approval means operation-only names. Revalidate name updates. Literals/parameters never name spans.

A private OpenTelemetry facade supplied only to JDBC filters before SDK samplers/processors. Keep upstream
sanitization on and parameter capture off; neither alone prevents text leakage. The entire output attribute allow-list
is safe `db.query.summary`, validated `db.operation.name`, `db.system.name`, `db.operation.batch.size`, and `error.type`.
Accept system values only from {postgresql, mysql, mariadb, oracle.db, microsoft.sql_server, h2database, sqlite, other_sql};
map only legacy h2->h2database, mssql->microsoft.sql_server, oracle->oracle.db; otherwise omit. Batch size: integer
in 0..1,000,000, otherwise omit. Read legacy db.system only when the stable system field is absent.
On ERROR emit only fixed `error.type=database_error`, preserving status with an empty description; no raw error codes,
class names or exception details. Omit operation attribute for `DB query`. Unknown keys/types/values are discarded.
Drop events, recorded exceptions and all link attributes; preserve native ids, parentage and timestamps. The facade's
metric/log providers are no-op. No SQL text/comments, credentials, connection/user details or results pass to the SDK.
This guarantee covers this facade's emissions only; parent spans, resource attributes, logs, samplers and processors
configured elsewhere can record/add sensitive data. Test both that boundary and JDBC exception propagation explicitly.

Preserve JDBC closing, unwrap/isWrapperFor, transactions and original results/exceptions. Fail telemetry safely without
retrying database work; preserve cancellation/interruption and fatal JVM errors rather than swallowing them as telemetry
failures. Document one JDBC instrumentation owner to avoid duplicate agent/wrapper spans. Application prerequisites:
non-blocking samplers/processors plus bounded asynchronous export. Arbitrary supplied SDKs, including synchronous
exporters, cannot receive a non-blocking guarantee. The supported pipeline is specified/tested below; the module neither
installs it nor adds an executor. No-op/sampled-out modes may still cost more than explicit disabled mode.

## Why this shape

Upstream owns interception/SQL analysis; Surefold owns context integration and filtering. An exporter-only filter
is too late for observers. Restricting summary grammar trades complex names for predictable privacy without a parser.
Checked 2026-10-31: [published POM](https://repo.maven.apache.org/maven2/io/opentelemetry/instrumentation/opentelemetry-jdbc/2.33.1-alpha/opentelemetry-jdbc-2.33.1-alpha.pom)
specifies API 1.67.0; [v2.33.1 sources](https://github.com/open-telemetry/opentelemetry-java-instrumentation/tree/v2.33.1/instrumentation/jdbc/library)
provide `wrap`. Runtime compatibility/performance still require evidence, not a zero-overhead claim.

## Stack

- [ ] **`codex/0266-otel-alignment`** — telemetry version alignment; existing context/test suites pass.
- [ ] **`codex/0266-jdbc-privacy`** — module/private facade; only the specified emissions reach SDK observers.
- [ ] **`codex/0266-jdbc-tracing`** — extension/disabled identity; execution, fallback and JDBC behaviour tests pass.
- [ ] **`codex/0266-jdbc-benchmark`** — reproducible harness/commands and raw measurements for the gate below.
- [ ] **`codex/0266-jdbc-evidence`** — compiled setup/pipeline guide, module/cookbook entries and passing gate evidence.

## Acceptance

Test plain/prepared/callable/batched execution, parent/trace ids across forks, isolation, execution-time parenting/root
spans, legacy/stable/dual/missing conventions in fresh JVMs, exact grammar/limits/fallbacks, close/unwrap/rollback,
original exceptions, telemetry faults, cancellation, no-op SDK and disabled identity with zero telemetry calls.
Inspect sampler/processor/exporter inputs plus logs/metrics with markers in SQL, comments, identifiers and failures;
cover bulk attributes, name updates, exception-log mode and an enclosing span that still records the propagated error.
Use `surefold-otel-test`, shared resources, fakes and real drivers where driver behaviour is the claim; no sleeps.

Gate workload: local Postgres 17 (resolve/commit an immutable image digest in the benchmark PR before measurements),
pgjdbc 42.7.7, HikariCP 7.1.0 (16 fixed connections), JDK 25/G1/2 GiB heap. Dedicated Linux amd64 host, eight physical
cores/16 GiB RAM minimum: pin client to four cores, Postgres to four others, disable SMT on those cores, no other load.
Record CPU model, kernel/JDK build, container limits and digest; compare every pair on that identical host/configuration.
One table `bench_rows(id BIGINT PRIMARY KEY, value BIGINT NOT NULL)`, 1,000,000 rows seeded with value=0; ANALYZE after
reset before each run. Sixteen workers each own a disjoint 62,500-id range, choose ids uniformly with java.util.Random seed 266+i,
and repeat four prepared `SELECT value FROM bench_rows WHERE id=?` then one prepared
`UPDATE bench_rows SET value=value+1 WHERE id=?`; autocommit, prepareThreshold=5, synchronous_commit=on, no think time.
Acquire/return a connection per operation; measure acquire-through-return latency and completed operations/second.
Use identical non-recording sampled parent contexts bound per worker in all modes; approve `bench_rows` at setup.

Compare original with disabled, enabled/no-op, sampled-out and 100%-recording modes, each with ten fresh-JVM baseline/
variant pairs, alternating execution order; 30 s warm-up then 60 s measurement per run. Reset data between runs.
Recording uses SDK 1.67.0 BatchSpanProcessor: queue 4096, batch 512, delay 200 ms, timeout 1 s; in-process discard
exporter, no span retention, non-blocking callbacks, no queue drops, flush completes within 10 s after measurement.
For each mode bootstrap paired per-run throughput loss and p95 increase (10,000 resamples, seed 266): the one-sided
95% upper confidence bound of each mean must be <=2%. Inconclusive/failing results do not pass; report all attempts.
Publish commands, raw runs, p50/p95/p99, CPU and allocations. Microbenchmarks for facade, repeated/distinct SQL and
batches are diagnostic, outside the 2% gate. Separately hold/fail the exporter to fill the bounded queue: with supported
pipeline queries keep completing, queue occupancy stays <=4096, and no exporter invocation runs on a query thread.
Required implementation checks: `./gradlew spotlessApply && ./gradlew build`; publish a separate benchmark gate command.

## Open questions

Nothing unresolved for implementation within this draft; the owner reviews the grammar, limits and concrete workload
before committing approval. Broader identifier syntax, fingerprints and whole-trace privacy are deferred to separate specs.
