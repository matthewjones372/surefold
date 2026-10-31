package io.github.matthewjones372.surefold.jdbc.bench

import com.sun.management.OperatingSystemMXBean
import com.sun.management.ThreadMXBean
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.jdbc.otel.traced
import io.github.matthewjones372.surefold.parMap
import io.opentelemetry.api.OpenTelemetry
import io.opentelemetry.api.trace.Span
import io.opentelemetry.api.trace.SpanContext
import io.opentelemetry.api.trace.TraceFlags
import io.opentelemetry.api.trace.TraceState
import io.opentelemetry.context.Context
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.lang.management.ManagementFactory
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Duration
import java.util.Random
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.math.ceil
import kotlin.time.TimeSource

fun main(args: Array<String>) {
    require(args.size == 2) { "mode result.json" }
    check(Runtime.getRuntime().maxMemory() == HEAP_BYTES) { "2 GiB heap required" }
    check(ManagementFactory.getGarbageCollectorMXBeans().all { it.name.startsWith("G1 ") }) { "G1 required" }
    check(Runtime.version().feature() == JDK_VERSION) { "JDK 25 required" }
    val mode = args[0]
    require(mode in setOf("original", "disabled", "noop", "sampled-out", "recording"))
    val exported = Shared(0L)
    val exporter = object : SpanExporter {
        override fun export(spans: Collection<SpanData>): CompletableResultCode {
            exported.update { it + spans.size }
            return CompletableResultCode.ofSuccess()
        }
        override fun flush() = CompletableResultCode.ofSuccess()
        override fun shutdown() = CompletableResultCode.ofSuccess()
    }
    val provider = provider(mode, exporter)
    val telemetry = if (mode in setOf("recording", "sampled-out")) {
        OpenTelemetrySdk.builder().setTracerProvider(provider).build()
    } else OpenTelemetry.noop()
    provider.use {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = requireNotNull(System.getenv("SUREFOLD_BENCH_JDBC_URL"))
                username = requireNotNull(System.getenv("PGUSER"))
                password = System.getenv("PGPASSWORD")
                maximumPoolSize = WORKERS
                minimumIdle = WORKERS
                isAutoCommit = true
                connectionInitSql = "SET synchronous_commit=on"
                addDataSourceProperty("prepareThreshold", "5")
            },
        ).use { pool ->
            List(WORKERS) { pool.connection }.forEach { it.close() }
            pool.verifyDatabase(
                requireNotNull(System.getenv("SUREFOLD_BENCH_EXPECTED_DATABASE")),
                requireNotNull(System.getenv("SUREFOLD_BENCH_EXPECTED_SYSTEM_IDENTIFIER")),
            )
            val source = when (mode) {
                "original" -> pool
                "disabled" -> pool.traced(telemetry, enabled = false)
                else -> pool.traced(telemetry, identifiers = setOf("bench_rows"))
            }
            val warmup = exercise(source, 30, retain = false)
            val cpu = ManagementFactory.getOperatingSystemMXBean() as OperatingSystemMXBean
            val threads = ManagementFactory.getThreadMXBean() as ThreadMXBean
            check(threads.isThreadAllocatedMemorySupported) { "allocation accounting unsupported" }
            threads.isThreadAllocatedMemoryEnabled = true
            val beforeAllocated = threads.totalThreadAllocatedBytes
            check(beforeAllocated >= 0) { "allocation accounting disabled" }
            val beforeCpu = cpu.processCpuTime
            val measurement = exercise(source, 60, retain = true)
            val cpuNanos = cpu.processCpuTime - beforeCpu
            val afterAllocated = threads.totalThreadAllocatedBytes
            check(afterAllocated >= beforeAllocated) { "allocation accounting unavailable" }
            val allocated = afterAllocated - beforeAllocated
            val flush = provider.forceFlush().join(10, TimeUnit.SECONDS)
            check(flush.isSuccess) { "flush did not succeed within ten seconds" }
            val operations = measurement.sumOf { it.count }
            val total = warmup.sumOf { it.count } + operations
            check(mode != "recording" || exported.peek() == total) { "recording lost spans" }
            val values = LongArray(operations.toInt())
            var offset = 0
            measurement.forEach { worker ->
                worker.latencies.copyInto(values, offset, 0, worker.count.toInt())
                offset += worker.count.toInt()
            }
            values.sort()
            fun percentile(p: Double) = values[(ceil(values.size * p).toInt() - 1).coerceAtLeast(0)]
            val elapsed = measurement.maxOf { it.elapsedNanos }
            val json = """
                {"mode":"$mode","operations":$operations,"elapsed_nanos":$elapsed,
                "throughput":${operations * 1e9 / elapsed},"p50_nanos":${percentile(.50)},
                "p95_nanos":${percentile(.95)},"p99_nanos":${percentile(.99)},
                "process_cpu_nanos":$cpuNanos,"allocation_bytes_approx":$allocated,
                "exported":${exported.peek()},"expected_spans":$total,"flush_success":true}
            """.trimIndent()
            Files.writeString(Path.of(args[1]), json + "\n")
        }
    }
}

private data class Worker(val count: Long, val elapsedNanos: Long, val latencies: LongArray)

private fun exercise(source: DataSource, seconds: Int, retain: Boolean): List<Worker> {
    val ready = Shared(0)
    val start = Promise<Nothing, TimeSource.Monotonic.ValueTimeMark>()
    return parMap(0 until WORKERS) { worker ->
        val latencies = if (retain) LongArray(1_000_000) else LongArray(0)
        val random = Random(266L + worker)
        val parent = SpanContext.create(
            "00000000000000000000000000000266", (worker + 1).toString(16).padStart(16, '0'),
            TraceFlags.getSampled(), TraceState.getDefault(),
        )
        Context.root().with(Span.wrap(parent)).makeCurrent().use {
            if (ready.modify { n -> (n + 1) to (n + 1) } == WORKERS) start.succeed(TimeSource.Monotonic.markNow())
            val mark = start.awaitValue()
            var count = 0L
            while (mark.elapsedNow().inWholeSeconds < seconds) {
                val id = worker.toLong() * IDS_PER_WORKER + random.nextInt(IDS_PER_WORKER) + 1
                val before = TimeSource.Monotonic.markNow()
                source.connection.use { connection -> connection.operation(id, update = count % 5 == 4L) }
                val latency = before.elapsedNow().inWholeNanoseconds
                if (retain) {
                    check(count < latencies.size) { "latency buffer exhausted" }
                    latencies[count.toInt()] = latency
                }
                count++
            }
            Worker(count, mark.elapsedNow().inWholeNanoseconds, latencies)
        }
    }
}

private fun Connection.operation(id: Long, update: Boolean) {
    val sql = if (update) "UPDATE bench_rows SET value=value+1 WHERE id=?"
    else "SELECT value FROM bench_rows WHERE id=?"
    prepareStatement(sql).use {
        it.setLong(1, id)
        if (update) check(it.executeUpdate() == 1)
        else it.executeQuery().use { rows -> check(rows.next()); check(rows.getLong(1) >= 0) }
    }
}

internal fun DataSource.verifyDatabase(
    expectedDatabase: String,
    expectedSystem: String,
) = connection.use { connection ->
    connection.createStatement().use { statement ->
        val sql = "SELECT current_database(), system_identifier::text FROM pg_control_system()"
        statement.executeQuery(sql).use { rows ->
            check(rows.next())
            check(rows.getString(1) == expectedDatabase)
            check(rows.getString(2) == expectedSystem)
        }
    }
}

private fun provider(mode: String, exporter: SpanExporter): SdkTracerProvider = SdkTracerProvider.builder().setSampler(
    if (mode == "sampled-out") Sampler.alwaysOff() else Sampler.alwaysOn(),
).apply {
    if (mode == "recording") addSpanProcessor(
        BatchSpanProcessor.builder(exporter).setMaxQueueSize(QUEUE_SIZE).setMaxExportBatchSize(BATCH_SIZE)
            .setScheduleDelay(Duration.ofMillis(200)).setExporterTimeout(Duration.ofSeconds(1)).build(),
    )
}.build()

private const val HEAP_BYTES = 2L * 1024 * 1024 * 1024
private const val JDK_VERSION = 25
private const val WORKERS = 16
private const val IDS_PER_WORKER = 62_500
private const val QUEUE_SIZE = 4096
private const val BATCH_SIZE = 512
