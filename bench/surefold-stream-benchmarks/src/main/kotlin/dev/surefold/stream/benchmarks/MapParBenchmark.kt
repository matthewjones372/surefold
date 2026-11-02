package dev.surefold.stream.benchmarks

import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.from
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
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val ELEMENTS = 10_000
private const val PARALLELISM = 8
private const val SCALE = 3L

internal fun parallelPipeline(ints: List<Int>): Run<Nothing, Long> =
    Stream.from(ints)
        .mapPar(PARALLELISM) { it.toLong() * SCALE }
        .runFold(0L) { total, n -> total + n }

internal fun parallelInts(): List<Int> = (0 until ELEMENTS).toList()

/**
 * A cheap body forked per element, so the row is the cost of the fork and the reordering, not the body.
 *
 * `pekko` is the same written against Pekko by hand, with no surefold in it: `mapAsync(8)` and a virtual
 * thread per element, which is what `mapPar` does on Pekko under its guard.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class MapParBenchmark {

    private val ints = (0 until ELEMENTS).toList()

    private val forks = Forks()

    @Benchmark
    fun surefold(pekko: Pekko): Long = parallelPipeline(ints).run(pekko.system).done()

    /** The same description on `Forks`: a window of eight bodies, refilled on the pulling thread. */
    @Benchmark
    fun forks(): Long = parallelPipeline(ints).run(forks).done()

    /** The same description on `Actors`: eight worker actors, told each element in turn. */
    @Benchmark
    fun actors(scoped: Scoped): Long = parallelPipeline(ints).run(scoped.actors).done()

    private val virtualThreads = Executors.newVirtualThreadPerTaskExecutor()

    @Benchmark
    fun pekko(pekko: Pekko): Long =
        Source.from(ints)
            .mapAsync(PARALLELISM) { n -> CompletableFuture.supplyAsync({ n.toLong() * SCALE }, virtualThreads) }
            .runWith(Sink.fold(0L) { total, n -> total + n }, pekko.system)
            .toCompletableFuture()
            .join()
}
