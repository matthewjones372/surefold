package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import org.apache.pekko.actor.typed.DispatcherSelector
import org.apache.pekko.actor.typed.javadsl.Adapter
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
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.typed.ActorRef as PekkoRef

private const val ACTORS = 100
private const val EACH = 10

/**
 * [ACTORS] actors, each handling [EACH] messages whose step blocks for a millisecond: the repository call an
 * actor makes. Pekko runs it on its default dispatcher (`pekkoDefault`, which the documentation warns against)
 * and on the blocking dispatcher it recommends (`pekkoBlocking`). The row is the whole burst.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class BlockingBenchmark {

    private lateinit var surefold: List<ActorRef<Slow>>
    private lateinit var pekkoDefault: List<PekkoRef<Slow>>
    private lateinit var pekkoBlocking: List<PekkoRef<Slow>>

    @Setup(Level.Trial)
    fun spawn(surefoldSystem: Surefold, pekko: Pekko) {
        surefold = (1..ACTORS).map { surefoldSystem.actor("slow-$it", surefoldSlow()) }
        pekkoDefault = (1..ACTORS).map { Adapter.spawn(pekko.system, pekkoSlow(), "default-$it") }
        pekkoBlocking = (1..ACTORS).map {
            Adapter.spawn(pekko.system, pekkoSlow(), "blocking-$it", DispatcherSelector.fromConfig("blocking"))
        }
    }

    @Benchmark
    fun surefold() = burst { done -> surefold.forEach { it.tell(Slow(done)) } }

    @Benchmark
    fun pekkoDefault() = burst { done -> pekkoDefault.forEach { it.tell(Slow(done)) } }

    @Benchmark
    fun pekkoBlocking() = burst { done -> pekkoBlocking.forEach { it.tell(Slow(done)) } }

    private fun burst(round: (CountDownLatch) -> Unit) {
        val done = CountDownLatch(ACTORS * EACH)
        repeat(EACH) { round(done) }
        done.await()
    }
}
