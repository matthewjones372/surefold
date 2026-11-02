package io.github.matthewjones372.surefold.actor.benchmarks

import dev.surefold.actor.ActorRef
import dev.surefold.actor.ask
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.AskPattern
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
import java.util.concurrent.TimeUnit
import kotlin.time.toKotlinDuration
import org.apache.pekko.actor.typed.ActorRef as PekkoRef
import org.apache.pekko.actor.typed.ActorSystem as TypedSystem

private const val ASKERS = 64
private const val ASKS_EACH = 1_000

/**
 * [ASKERS] virtual threads each asking one actor [ASKS_EACH] times, as a service whose every request asks the one
 * actor that owns its state. The actor's mailbox empties between most asks, so its runner keeps running out of work.
 * The row is per ask.
 */
@State(Scope.Benchmark)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@OperationsPerInvocation(ASKERS * ASKS_EACH)
@Warmup(iterations = 5, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
open class AskBenchmark {

    private lateinit var surefold: ActorRef<SurefoldQuestion>
    private lateinit var pekko: PekkoRef<PekkoQuestion>
    private lateinit var pekkoSystem: TypedSystem<*>

    @Setup(Level.Trial)
    fun spawn(surefoldSystem: Surefold, pekko: Pekko) {
        surefold = surefoldSystem.actor("answerer", surefoldAnswerer())
        this.pekko = Adapter.spawn(pekko.system, pekkoAnswerer(), "answerer")
        pekkoSystem = Adapter.toTyped(pekko.system)
    }

    @Benchmark
    fun surefold() = askers {
        checkNotNull(
            surefold.ask<SurefoldQuestion, Int>(ASK_WITHIN.toKotlinDuration()) { SurefoldQuestion(1, it) }.getOrNull(),
        )
    }

    @Benchmark
    fun pekko() = askers {
        AskPattern.ask(pekko, { reply: PekkoRef<Int> -> PekkoQuestion(1, reply) }, ASK_WITHIN, pekkoSystem.scheduler())
            .toCompletableFuture()
            .join()
    }

    private fun askers(ask: () -> Unit) {
        val threads = List(ASKERS) { Thread.ofVirtual().start { repeat(ASKS_EACH) { ask() } } }
        threads.forEach(Thread::join)
    }
}
