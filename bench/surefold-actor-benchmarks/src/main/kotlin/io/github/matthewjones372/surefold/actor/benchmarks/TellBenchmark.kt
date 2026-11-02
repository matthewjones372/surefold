package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.openjdk.jmh.annotations.Benchmark
import org.openjdk.jmh.annotations.BenchmarkMode
import org.openjdk.jmh.annotations.Fork
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Measurement
import org.openjdk.jmh.annotations.Mode
import org.openjdk.jmh.annotations.OperationsPerInvocation
import org.openjdk.jmh.annotations.OutputTimeUnit
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.Warmup
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.apache.pekko.actor.typed.ActorRef as PekkoRef

private const val MESSAGES = 100_000
private const val SENDERS = 8

/**
 * `tell` into one actor until [MESSAGES] have been handled: from the benchmark thread (`oneToOne`), and from
 * [SENDERS] virtual threads at once (`manyToOne`). The row is per message.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(MESSAGES)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class TellBenchmark {

    private lateinit var surefold: ActorRef<Hit>
    private lateinit var pekko: PekkoRef<Hit>

    @Setup(Level.Trial)
    fun spawn(surefoldSystem: Surefold, pekkoSystem: Pekko) {
        surefold = surefoldSystem.actor("counter", surefoldCounter())
        pekko = Adapter.spawn(pekkoSystem.system, pekkoCounter(), "counter")
    }

    @Benchmark
    fun surefoldOneToOne() = hits(1) { hit -> surefold.tell(hit) }

    @Benchmark
    fun pekkoOneToOne() = hits(1) { hit -> pekko.tell(hit) }

    @Benchmark
    fun surefoldManyToOne() = hits(SENDERS) { hit -> surefold.tell(hit) }

    @Benchmark
    fun pekkoManyToOne() = hits(SENDERS) { hit -> pekko.tell(hit) }

    private fun hits(senders: Int, tell: (Hit) -> Unit) {
        val done = CountDownLatch(MESSAGES)
        val hit = Hit(done)
        if (senders == 1) {
            repeat(MESSAGES) { tell(hit) }
        } else {
            List(senders) { Thread.ofVirtual().start { repeat(MESSAGES / senders) { tell(hit) } } }
                .forEach { it.join() }
        }
        done.await()
    }
}
