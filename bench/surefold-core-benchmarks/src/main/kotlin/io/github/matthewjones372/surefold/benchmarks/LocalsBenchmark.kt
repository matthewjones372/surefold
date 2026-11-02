package io.github.matthewjones372.surefold.benchmarks

import io.github.matthewjones372.surefold.Detach
import io.github.matthewjones372.surefold.Logger
import io.github.matthewjones372.surefold.Shared
import io.github.matthewjones372.surefold.logAnnotated
import io.github.matthewjones372.surefold.logInfo
import io.github.matthewjones372.surefold.logger
import io.github.matthewjones372.surefold.scope
import io.github.matthewjones372.surefold.surefoldLocal
import io.github.matthewjones372.surefold.transact
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val READS = 100

/** A logger that keeps nothing, so a line costs what surefold does to build it and no more. */
private val quiet = Logger { }

/**
 * The quiet logger bound once for the whole trial, as a service binds its logger at start (spec 0280). Bound by the
 * benchmark thread itself, which is the one a [Scope.Thread] state's setup runs on.
 */
@State(Scope.Thread)
open class BoundLogger {
    private lateinit var detach: Detach

    @Setup(Level.Trial)
    fun bind() {
        detach = logger.attach(quiet)
    }

    @TearDown(Level.Trial)
    fun unbind() = detach.detach()
}

/**
 * What spec 0274 and 0275 change: a `SurefoldLocal` read, a binding, a log line with annotations, a fork that
 * inherits, and a `transact`, each on whatever backs them at the time it runs. Run it on both sides of a change.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class LocalsBenchmark {

    private val local = surefoldLocal { 0 }
    private val shared = Shared(0)
    private lateinit var pool: ExecutorService

    @Setup(Level.Trial)
    fun start() {
        pool = Executors.newFixedThreadPool(2)
    }

    @TearDown(Level.Trial)
    fun stop() {
        pool.shutdownNow()
    }

    @Benchmark
    fun readUnbound(): Int = local.get()

    @Benchmark
    fun bindAndRead(): Int = local.locally(1) { local.get() }

    /** [READS] reads inside one binding: the read's own cost, once the binding's is spread over them. */
    @Benchmark
    fun readsInsideABinding(): Int = local.locally(1) {
        var sum = 0
        repeat(READS) { sum += local.get() }
        sum
    }

    @Benchmark
    fun annotatedLogLine() = logger.locally(quiet) {
        logAnnotated("request_id" to "r-1", "tenant" to "acme") { logInfo("handled") }
    }

    /** A line under a logger bound once, with nothing annotated: what each line of a service costs (spec 0280). */
    @Benchmark
    fun logLine(@Suppress("UNUSED_PARAMETER") bound: BoundLogger) = logInfo("handled")

    /** The same line with two annotations bound around it, the logger still bound once. */
    @Benchmark
    fun annotatedLogLineUnderABoundLogger(@Suppress("UNUSED_PARAMETER") bound: BoundLogger) =
        logAnnotated("request_id" to "r-1", "tenant" to "acme") { logInfo("handled") }

    @Benchmark
    fun forkInheritsABinding(): Int = local.locally(1) { scope { async { local.get() }.await() } }

    /** A fork on a pool, awaited from outside it: the executor's own thread runs the body. */
    @Benchmark
    fun forkOnAPool(): Int = scope(on = pool) { async { local.get() }.await() }

    @Benchmark
    fun transactOnce() = shared.update { it + 1 }

    @Benchmark
    fun transactNested() = transact { shared.update { it + 1 } }
}
