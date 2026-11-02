package dev.surefold.stream.benchmarks

import dev.surefold.stream.Exit
import org.apache.pekko.actor.ActorSystem
import org.openjdk.jmh.annotations.Level
import org.openjdk.jmh.annotations.Scope
import org.openjdk.jmh.annotations.Setup
import org.openjdk.jmh.annotations.State
import org.openjdk.jmh.annotations.TearDown
import java.util.concurrent.CompletionStage

/** One actor system per fork, which is what a service has: started once, run on many times. */
@State(Scope.Benchmark)
open class Pekko {

    lateinit var system: ActorSystem

    @Setup(Level.Trial)
    fun start() {
        system = ActorSystem.create("surefold-stream-benchmarks")
    }

    @TearDown(Level.Trial)
    fun stop() {
        system.terminate()
        system.getWhenTerminated().toCompletableFuture().join()
    }
}

/** A run that did not end `Done` would be measuring a different pipeline, so it stops the benchmark. */
internal fun <E, R> CompletionStage<Exit<E, R>>.done(): R =
    when (val exit = toCompletableFuture().join()) {
        is Exit.Done -> exit.value
        is Exit.Failed -> error("the benchmark pipeline failed: ${exit.error}")
        is Exit.Died -> throw exit.cause
    }
