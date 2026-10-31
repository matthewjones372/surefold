package io.github.matthewjones372.surefold.jdbc.otel

import com.zaxxer.hikari.HikariDataSource
import io.github.matthewjones372.surefold.Promise
import io.github.matthewjones372.surefold.Resource
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.actor.journal.jdbc.Postgres
import io.github.matthewjones372.surefold.awaitValue
import io.github.matthewjones372.surefold.junit.SharedSuite
import io.github.matthewjones372.surefold.junit.expect
import io.github.matthewjones372.surefold.junit.shouldBe
import io.github.matthewjones372.surefold.parMap
import io.github.matthewjones372.surefold.resource
import io.github.matthewjones372.surefold.timeoutOrNull
import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.sdk.OpenTelemetrySdk
import io.opentelemetry.sdk.common.CompletableResultCode
import io.opentelemetry.sdk.common.InternalTelemetryVersion
import io.opentelemetry.sdk.metrics.InstrumentType
import io.opentelemetry.sdk.metrics.SdkMeterProvider
import io.opentelemetry.sdk.metrics.data.AggregationTemporality
import io.opentelemetry.sdk.metrics.data.MetricData
import io.opentelemetry.sdk.metrics.export.CollectionRegistration
import io.opentelemetry.sdk.metrics.export.MetricReader
import io.opentelemetry.sdk.trace.SdkTracerProvider
import io.opentelemetry.sdk.trace.data.SpanData
import io.opentelemetry.sdk.trace.export.BatchSpanProcessor
import io.opentelemetry.sdk.trace.export.SpanExporter
import io.opentelemetry.sdk.trace.samplers.Sampler
import java.time.Duration
import java.util.concurrent.TimeUnit
import javax.sql.DataSource
import kotlin.time.Duration.Companion.seconds

object BoundedExporterSuite : SharedSuite<DataSource>() {
    override val shared: Resource<DataSource> = resource {
        val pool = install({ Postgres.empty() as HikariDataSource }) { value, _ -> value.close() }
        pool.connection.use { connection ->
            connection.createStatement().use {
                it.execute("CREATE TABLE bench_rows(id BIGINT PRIMARY KEY, value BIGINT NOT NULL)")
                it.execute("INSERT INTO bench_rows SELECT id, 0 FROM generate_series(1, 32) id")
            }
        }
        pool
    }

    init {
        listOf(true, false).forEach { succeeds ->
            val outcome = if (succeeds) "succeeds" else "fails"
            test("a held exporter that $outcome leaves queries running with a bounded queue") { pool ->
                val exporter = HeldExporter(succeeds)
                val reader = QueueReader()
                SdkMeterProvider.builder().registerMetricReader(reader).build().use { meters ->
                    val processor = BatchSpanProcessor.builder(exporter)
                        .setMaxQueueSize(4096)
                        .setMaxExportBatchSize(512)
                        .setScheduleDelay(Duration.ofMillis(200))
                        .setExporterTimeout(Duration.ofSeconds(1))
                        .setInternalTelemetryVersion(InternalTelemetryVersion.LEGACY)
                        .setMeterProvider(meters)
                        .build()
                    val provider = SdkTracerProvider.builder().setSampler(Sampler.alwaysOn())
                        .addSpanProcessor(processor).build()
                    val queryThreads = Shared(emptySet<Long>())
                    try {
                        val telemetry = OpenTelemetrySdk.builder().setTracerProvider(provider).build()
                        val source = pool.traced(telemetry, identifiers = setOf("bench_rows"))
                        val completed = Shared(0)
                        val firstId = if (succeeds) 1 else 17
                        fun update(id: Int) {
                            queryThreads.update { it + Thread.currentThread().threadId() }
                            source.increment(id)
                            completed.update { it + 1 }
                        }
                        repeat(512) { update(firstId + it % 16) }
                        exporter.entered.awaitValue(5.seconds) shouldBe Unit
                        reader.queueSize().let { expect(it in 0..4096) }
                        val finished = timeoutOrNull(20.seconds) {
                            parMap(0 until 16) { worker -> repeat(320) { update(firstId + worker) } }
                            true
                        }
                        finished shouldBe true
                        completed.peek() shouldBe 5632
                        expect(exporter.release.peek() == null)
                        reader.queueSize() shouldBe 4096L
                        expect(reader.droppedSpans() > 0)
                        expect(exporter.threads.peek().intersect(queryThreads.peek()).isEmpty())
                        pool.totalValue(firstId) shouldBe 5632L
                    } finally {
                        // Release before shutdown: a callback blocked inside export cannot observe its result timeout.
                        exporter.release.succeed(Unit)
                        val shutdown = provider.shutdown().join(10, TimeUnit.SECONDS)
                        expect(shutdown.isSuccess)
                    }
                    expect(exporter.calls.peek() > 0)
                    expect(exporter.threads.peek().intersect(queryThreads.peek()).isEmpty())
                    exporter.timedOut.peek() shouldBe false
                }
            }
        }
    }
}

private class HeldExporter(private val succeeds: Boolean) : SpanExporter {
    val entered = Promise<Nothing, Unit>()
    val release = Promise<Nothing, Unit>()
    val threads = Shared(emptySet<Long>())
    val calls = Shared(0)
    val timedOut = Shared(false)

    override fun export(spans: Collection<SpanData>): CompletableResultCode {
        threads.update { it + Thread.currentThread().threadId() }
        calls.update { it + 1 }
        entered.succeed(Unit)
        if (release.awaitValue(30.seconds) == null) timedOut.update { true }
        return if (succeeds) CompletableResultCode.ofSuccess() else CompletableResultCode.ofFailure()
    }

    override fun flush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()
}

private fun QueueReader.queueSize(): Long = collectAllMetrics()
    .single { it.name == "queueSize" }
    .longGaugeData.points.single {
        it.attributes.get(AttributeKey.stringKey("processorType")) == "BatchSpanProcessor"
    }.value

private fun QueueReader.droppedSpans(): Long = collectAllMetrics()
    .single { it.name == "processedSpans" }
    .longSumData.points.filter {
        it.attributes.get(AttributeKey.booleanKey("dropped")) == true
    }.sumOf { it.value }

private fun DataSource.increment(id: Int) = connection.use { connection ->
    connection.prepareStatement("UPDATE bench_rows SET value=value+1 WHERE id=?").use {
        it.queryTimeout = 5
        it.setInt(1, id)
        it.executeUpdate() shouldBe 1
    }
}

private fun DataSource.totalValue(firstId: Int): Long = connection.use { connection ->
    connection.prepareStatement("SELECT SUM(value) FROM bench_rows WHERE id>=? AND id<?").use {
        it.queryTimeout = 5
        it.setInt(1, firstId)
        it.setInt(2, firstId + 16)
        it.executeQuery().use { rows ->
            expect(rows.next())
            rows.getLong(1)
        }
    }
}

/** sdk-testing registers another ContextStorageProvider, so collect through the SDK's public reader SPI. */
private class QueueReader : MetricReader {
    private val registration = Shared(CollectionRegistration.noop())

    override fun register(collectionRegistration: CollectionRegistration) {
        registration.update { collectionRegistration }
    }

    override fun getAggregationTemporality(instrumentType: InstrumentType) = AggregationTemporality.CUMULATIVE

    override fun forceFlush(): CompletableResultCode = CompletableResultCode.ofSuccess()

    override fun shutdown(): CompletableResultCode = CompletableResultCode.ofSuccess()

    fun collectAllMetrics(): Collection<MetricData> = registration.peek().collectAllMetrics()
}
