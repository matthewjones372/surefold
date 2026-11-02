package io.github.matthewjones372.surefold.actor.benchmarks

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

private const val HOPS = 100

/**
 * A ball bounced [HOPS] times between two actors, each hop a `tell` from one step to the other. Sampled, so
 * the result carries p50 and p99 of a whole rally; a hop is the rally divided by [HOPS].
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class PingPongBenchmark {

    private lateinit var surefoldLeft: ActorRef<SurefoldBall>
    private lateinit var surefoldRight: ActorRef<SurefoldBall>
    private lateinit var pekkoLeft: PekkoRef<PekkoBall>
    private lateinit var pekkoRight: PekkoRef<PekkoBall>

    @Setup(Level.Trial)
    fun spawn(surefold: Surefold, pekko: Pekko) {
        surefoldLeft = surefold.actor("left", surefoldPaddle())
        surefoldRight = surefold.actor("right", surefoldPaddle())
        pekkoLeft = Adapter.spawn(pekko.system, pekkoPaddle(), "left")
        pekkoRight = Adapter.spawn(pekko.system, pekkoPaddle(), "right")
    }

    @Benchmark
    fun surefold() {
        val done = CountDownLatch(1)
        surefoldLeft.tell(SurefoldBall(HOPS, surefoldRight, done))
        done.await()
    }

    @Benchmark
    fun pekko() {
        val done = CountDownLatch(1)
        pekkoLeft.tell(PekkoBall(HOPS, pekkoRight, done))
        done.await()
    }
}
