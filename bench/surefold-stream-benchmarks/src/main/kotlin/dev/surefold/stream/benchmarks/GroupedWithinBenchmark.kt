package dev.surefold.stream.benchmarks

import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.from
import dev.surefold.stream.groupedWithin
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

private const val ELEMENTS = 100_000
private const val BATCH = 100

internal fun batchedPipeline(ints: List<Int>): Run<Nothing, Int> =
    Stream.from(ints)
        .groupedWithin(BATCH, 1.seconds)
        .runFold(0) { total, batch -> total + batch.size }

internal fun batchedInts(): List<Int> = (0 until ELEMENTS).toList()

/** Batches that always fill before the window closes, so the row is the operator and not the clock. */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class GroupedWithinBenchmark {

    private val ints = (0 until ELEMENTS).toList()

    @Benchmark
    fun surefold(pekko: Pekko): Int = batchedPipeline(ints).run(pekko.system).done()

    private val forks = Forks()

    @Benchmark
    fun forks(): Int = batchedPipeline(ints).run(forks).done()

    @Benchmark
    fun actors(scoped: Scoped): Int = batchedPipeline(ints).run(scoped.actors).done()
}
