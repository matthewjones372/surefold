package io.github.matthewjones372.surefold.stream.benchmarks

import dev.surefold.stream.Forks
import dev.surefold.stream.Run
import dev.surefold.stream.Stream
import dev.surefold.stream.StreamBackend
import dev.surefold.stream.buffer
import dev.surefold.stream.from
import dev.surefold.stream.merge
import dev.surefold.stream.run
import dev.surefold.stream.runFold
import dev.surefold.stream.start
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

private const val RUNS = 1_000
private const val ELEMENTS = 10_000
private const val BUFFERED = 64

private val request = (1..10).toList()

private val short: Run<Nothing, Long> = Stream.from(request).runFold(0L) { total, n -> total + n }

/** [RUNS] short runs started at once and all awaited: what a service answering many requests at a time does. */
private fun StreamBackend.manyAtOnce(): Long =
    List(RUNS) { short.start(this) }.sumOf { it.exit.done() }

/**
 * Many runs at once: on `Forks` each run is a fork of its own, and on `Actors` each is an actor sharing the scope's
 * runners. The row is one run of the thousand.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(RUNS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class ManyRunsBenchmark {

    private val forks = Forks()

    @Benchmark
    fun forks(): Long = forks.manyAtOnce()

    @Benchmark
    fun actors(scoped: Scoped): Long = scoped.actors.manyAtOnce()
}

private val ints = (0 until ELEMENTS).toList()

// Two inputs of half the elements each, so that the row is one element whichever input it came from.
private val merged: Run<Nothing, Long> =
    Stream.from(ints.subList(0, ELEMENTS / 2)).merge(Stream.from(ints.subList(ELEMENTS / 2, ELEMENTS)))
        .runFold(0L) { total, n -> total + n }

private val buffered: Run<Nothing, Long> =
    Stream.from(ints).buffer(BUFFERED).runFold(0L) { total, n -> total + n }

/**
 * The operators that hand elements from one thread to another: `merge` and `buffer`. On `Forks` each input is a fork
 * putting into a blocking queue; on `Actors` each is an actor putting into one, a batch a step. The row is one element.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ELEMENTS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class HandOffBenchmark {

    private val forks = Forks()

    @Benchmark
    fun mergeForks(): Long = merged.run(forks).done()

    @Benchmark
    fun mergeActors(scoped: Scoped): Long = merged.run(scoped.actors).done()

    @Benchmark
    fun bufferForks(): Long = buffered.run(forks).done()

    @Benchmark
    fun bufferActors(scoped: Scoped): Long = buffered.run(scoped.actors).done()
}
