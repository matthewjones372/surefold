package dev.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
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

private const val FAN = 10_000

/**
 * One message to each of [FAN] idle actors, until all are handled: the case a linger before parking would pay
 * once per actor, if an actor that only receives lingered. `surefold` and `pekko` are the whole fan-out;
 * `surefoldTells` and `pekkoTells` time only the loop of tells, with the actors' handling of the last one waited out
 * untimed.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class FanOutBenchmark {

    private lateinit var surefold: List<ActorRef<Hit>>
    private lateinit var pekko: List<PekkoRef<Hit>>

    // The fan-out the last tells-only invocation started, which the next waits out before it is timed.
    private var telling: CountDownLatch? = null

    @Setup(Level.Trial)
    fun spawn(surefoldSystem: Surefold, pekkoSystem: Pekko) {
        surefold = (1..FAN).map { surefoldSystem.actor("fan-$it", surefoldCounter()) }
        pekko = (1..FAN).map { Adapter.spawn(pekkoSystem.system, pekkoCounter(), "fan-$it") }
    }

    @Benchmark
    fun surefold() = fanOut { hit -> surefold.forEach { it.tell(hit) } }

    @Benchmark
    fun pekko() = fanOut { hit -> pekko.forEach { it.tell(hit) } }

    @Setup(Level.Invocation)
    fun settle() {
        telling?.await()
        telling = null
    }

    @Benchmark
    fun surefoldTells() = tellsOnly { hit -> surefold.forEach { it.tell(hit) } }

    @Benchmark
    fun pekkoTells() = tellsOnly { hit -> pekko.forEach { it.tell(hit) } }

    private fun tellsOnly(tellAll: (Hit) -> Unit) {
        val done = CountDownLatch(FAN)
        telling = done
        tellAll(Hit(done))
    }

    private fun fanOut(tellAll: (Hit) -> Unit) {
        val done = CountDownLatch(FAN)
        tellAll(Hit(done))
        done.await()
    }
}
