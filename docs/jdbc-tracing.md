# JDBC query traces

Wrap the application's pool once and use the returned `DataSource` everywhere.
This module is unreleased in this checkout (`0.17.1-SNAPSHOT`); publication remains pending.
Qualified performance measurements are deferred. For a consumer subproject in this checkout:

```kotlin
dependencies {
    implementation(project(":surefold-jdbc-otel"))
    implementation("io.opentelemetry:opentelemetry-sdk:1.67.0")
}
```

The integration pins `io.opentelemetry.instrumentation:opentelemetry-jdbc:2.33.1-alpha`;
it supplies no driver or pool. The SDK dependency supports the application-owned pipeline below.

Start the JVM with `-Dotel.semconv-stability.opt-in=database` **before instrumentation
initializes**. This selects database conventions JVM-wide. The wrapper never changes
global settings; missing, conflicting or already-initialized conventions fall back safely.
Choose one JDBC instrumentation owner: disable duplicate JDBC agent instrumentation
when using this wrapper, and never wrap an already traced source again.

<!-- jdbc-tracing -->
```kotlin
import io.github.matthewjones372.surefold.jdbc.otel.traced
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

fun jdbcTelemetry(exporter: SpanExporter): OpenTelemetrySdk {
    val processor = BatchSpanProcessor.builder(exporter)
        .setMaxQueueSize(4096)
        .setMaxExportBatchSize(512)
        .setScheduleDelay(200, TimeUnit.MILLISECONDS)
        .setExporterTimeout(1, TimeUnit.SECONDS)
        .build()
    val provider = SdkTracerProvider.builder()
        .setSampler(Sampler.alwaysOn())
        .addSpanProcessor(processor)
        .build()
    return OpenTelemetrySdk.builder().setTracerProvider(provider).build()
}

fun tracedDatabase(pool: DataSource, telemetry: OpenTelemetry): DataSource =
    pool.traced(telemetry, identifiers = setOf("bookings", "payments"))

fun tracingDisabled(pool: DataSource, telemetry: OpenTelemetry): DataSource {
    val disabled = pool.traced(telemetry, enabled = false)
    check(disabled === pool)
    return disabled
}

fun flushJdbcTelemetry(telemetry: OpenTelemetrySdk) {
    check(telemetry.sdkTracerProvider.forceFlush().join(10, TimeUnit.SECONDS).isSuccess)
}
```

The application owns and closes its pool and SDK. Flush after stopping database work,
then close the SDK. Configure exporter shutdown separately to fit the application's deadline.
`traced(telemetry, enabled = true, identifiers = emptySet())` enables tracing by default.
Disabled returns the original pool before identifier validation, telemetry access, initialization
or wrapper allocation; even invalid identifiers are ignored when disabled.
No-op and sampled-out telemetry remain supported; neither promises disabled-mode cost.

Executions automatically create CLIENT spans under the request/job current at execution,
including Surefold forks; preparation captures no parent. Without a parent they are roots.
No per-query wrapping is required. `SELECT bookings` can name a span only when that exact
identifier is approved; approvals are copied at setup, at most 256 case-sensitive names
matching `[a-z_][a-z0-9_]{0,62}`. Invalid configuration fails with an input-free message.
Empty approvals produce operation-only names. Complex/unapproved summaries fall back to a
validated operation, then `DB query`; initial names and query text never supply fallbacks.

Before SDK samplers/processors, the JDBC facade admits only validated query summary,
operation, database system and batch size, plus fixed `error.type=database_error` on ERROR.
For example, a valid emitted identity is name/`db.query.summary`=`SELECT bookings`, with
`db.operation.name=SELECT`, `db.system.name=postgresql` and an optional integer
`db.operation.batch.size=2`. Unknown fields and invalid values are omitted.
It removes query text, parameters, connection details, events, exception details and link
attributes, preserving native SDK ids, parentage and timestamps. It does not manufacture
trace/span ids. Name/identity freeze before sampling; later naming mutations are ignored.
Its log and metric providers are no-op. Enclosing spans, resource attributes and independently
configured samplers/processors/loggers can still record sensitive data, including propagated
JDBC exceptions. This boundary covers the wrapper's emissions, not the whole trace.

Keep samplers and processor callbacks non-blocking and export asynchronously through a
bounded queue, as above. Arbitrary supplied SDKs, including synchronous exporters, carry no
non-blocking guarantee. A full queue can drop telemetry; monitor drops and exporter failures.
The module installs no SDK or executor. No zero-overhead or <=2% claim is established:
the owner deferred spec 0266's qualified paired measurements. The benchmark remains available for later evaluation.
