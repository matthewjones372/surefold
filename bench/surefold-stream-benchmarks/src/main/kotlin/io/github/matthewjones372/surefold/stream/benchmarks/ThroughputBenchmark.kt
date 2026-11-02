package io.github.matthewjones372.surefold.stream.benchmarks

import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.filter
import dev.surefold.stream.from
import dev.surefold.stream.grouped
import dev.surefold.stream.map
import dev.surefold.stream.mapOrFail
import dev.surefold.stream.mapPar
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.apache.pekko.stream.javadsl.Sink
import org.apache.pekko.stream.javadsl.Source
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val LINES = 100_000
private const val BATCH = 100
private const val KINDS = 7

internal data class Payment(val id: Int, val pence: Long)

internal data class Unparsed(val line: String)

/** CSV lines as a service reads them: an id and an amount, some of them refunds (not above zero). */
internal fun paymentLines(): List<String> = (0 until LINES).map { i -> "$i,${(i % KINDS - 1) * 100}" }

/** Parse, validate, drop refunds, double, batch by a hundred, and count what was batched. */
internal fun ingest(lines: List<String>): Run<Unparsed, Int> =
    Stream.from(lines)
        .map { line -> line.split(',') }
        .mapOrFail { fields ->
            Payment(fields[0].toInt(), fields[1].toLongOrNull() ?: fail(Unparsed(fields.joinToString(","))))
        }
        .filter { payment -> payment.pence > 0 }
        .map { payment -> payment.copy(pence = payment.pence * 2) }
        .grouped(BATCH)
        .runFold(0) { count, batch -> count + batch.size }

/**
 * Throughput to quote, in elements a second: a CPU-light ingest of a hundred thousand CSV lines through
 * five stages and a batch. `pekko` is the same pipeline written against Pekko by hand, with no surefold in it.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(LINES)
@Warmup(iterations = 5, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
open class IngestThroughputBenchmark {

    private val lines = paymentLines()

    private val described = ingest(lines)

    private val forks = Forks()

    @Benchmark
    fun forks(): Int = described.run(forks).done()

    @Benchmark
    fun actors(scoped: Scoped): Int = described.run(scoped.actors).done()

    @Benchmark
    fun surefold(pekko: Pekko): Int = described.run(pekko.system).done()

    @Benchmark
    fun pekko(pekko: Pekko): Int =
        Source.from(lines)
            .map { line -> line.split(',') }
            .map { fields -> Payment(fields[0].toInt(), fields[1].toLong()) }
            .filter { payment -> payment.pence > 0 }
            .map { payment -> payment.copy(pence = payment.pence * 2) }
            .grouped(BATCH)
            .runWith(Sink.fold(0) { count, batch -> count + batch.size }, pekko.system)
            .toCompletableFuture()
            .join()
}

private const val CALLS = 2_000
private const val IN_FLIGHT = 16
private const val LATENCY_MS = 1L

/** A call that blocks for a millisecond, as a lookup against a nearby service does. */
// Blocking is what this measures: the ban on sleep is for library code, and this stands in for a call.
@Suppress("ForbiddenMethodCall")
private fun lookup(id: Int): Long {
    Thread.sleep(LATENCY_MS)
    return id * 2L
}

internal fun enrich(ids: List<Int>): Run<Nothing, Long> =
    Stream.from(ids).mapPar(IN_FLIGHT) { id -> lookup(id) }.runFold(0L) { total, n -> total + n }

/**
 * Throughput to quote when each element waits on a call: sixteen calls of a millisecond in flight, so
 * every backend is bounded near sixteen thousand a second, and the rows show how close each gets.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@OperationsPerInvocation(CALLS)
@Warmup(iterations = 3, time = 2)
@Measurement(iterations = 5, time = 2)
@Fork(2)
open class EnrichThroughputBenchmark {

    private val ids = (0 until CALLS).toList()

    private val described = enrich(ids)

    private val forks = Forks()

    private val virtualThreads = Executors.newVirtualThreadPerTaskExecutor()

    @TearDown
    fun stop() = virtualThreads.close()

    @Benchmark
    fun forks(): Long = described.run(forks).done()

    @Benchmark
    fun actors(scoped: Scoped): Long = described.run(scoped.actors).done()

    @Benchmark
    fun surefold(pekko: Pekko): Long = described.run(pekko.system).done()

    @Benchmark
    fun pekko(pekko: Pekko): Long =
        Source.from(ids)
            .mapAsync(IN_FLIGHT) { id -> CompletableFuture.supplyAsync({ lookup(id) }, virtualThreads) }
            .runWith(Sink.fold(0L) { total, n -> total + n }, pekko.system)
            .toCompletableFuture()
            .join()
}
