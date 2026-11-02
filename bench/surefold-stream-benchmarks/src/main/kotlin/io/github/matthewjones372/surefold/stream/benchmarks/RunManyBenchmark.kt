package io.github.matthewjones372.surefold.stream.benchmarks

import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.filter
import dev.surefold.stream.from
import dev.surefold.stream.map
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.TimeUnit

private val request = listOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)

internal fun perRequestPipeline(): Run<Nothing, Long> =
    Stream.from(request)
        .map { it.toLong() }
        .filter { it % 2 == 0L }
        .runFold(0L) { total, n -> total + n }

/**
 * A short pipeline per request: the cost of starting a run, not of its elements.
 *
 * `describedOnce` is the shape 0046 has to keep cheap once a description is compiled rather than
 * built; `describedEachTime` is what building the stages costs on top.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class RunManyBenchmark {

    private val described = perRequestPipeline()

    @Benchmark
    fun describedOnce(pekko: Pekko): Long = described.run(pekko.system).done()

    @Benchmark
    fun describedEachTime(pekko: Pekko): Long = perRequestPipeline().run(pekko.system).done()

    private val forks = Forks()

    /** Starting a run on `Forks` is one fork and one loop, with no graph to materialise. */
    @Benchmark
    fun describedOnceForks(): Long = described.run(forks).done()

    /** Starting a run on `Actors` is one actor spawned on a scope that is already running. */
    @Benchmark
    fun describedOnceActors(scoped: Scoped): Long = described.run(scoped.actors).done()
}
