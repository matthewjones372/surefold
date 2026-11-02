package io.github.matthewjones372.surefold.actor.benchmarks

import com.typesafe.config.ConfigFactory
import dev.surefold.scope
import io.github.matthewjones372.surefold.actor.awaitIdle
import io.github.matthewjones372.surefold.actor.spawn
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import java.util.concurrent.CountDownLatch

private const val IDLE_ACTORS = 100_000
private const val SETTLE_GCS = 5

/**
 * Heap per idle actor: [IDLE_ACTORS] spawned, each handed one message so it has run, then left idle while the
 * heap is measured. A whole-heap difference after GC, so it counts everything an actor keeps alive.
 */
fun main() {
    val surefold = scope {
        measured {
            val refs = (1..IDLE_ACTORS).map { spawn("idle-$it", surefoldCounter()) }
            val done = CountDownLatch(IDLE_ACTORS)
            refs.forEach { it.tell(Hit(done)) }
            done.await()
            awaitIdle()
            refs
        }
    }
    val system = ActorSystem.create("footprint", ConfigFactory.load())
    val pekko = measured {
        val refs = (1..IDLE_ACTORS).map { Adapter.spawn(system, pekkoCounter(), "idle-$it") }
        val done = CountDownLatch(IDLE_ACTORS)
        refs.forEach { it.tell(Hit(done)) }
        done.await()
        refs
    }
    system.terminate()
    println("surefold:  $surefold bytes per idle actor")
    println("pekko: $pekko bytes per idle actor")
}

/** The heap [spawnAll]'s actors keep, each; its list is read after the second measurement, so it is still live. */
private fun <R> measured(spawnAll: () -> List<R>): Long {
    val before = settledHeap()
    val refs = spawnAll()
    val after = settledHeap()
    check(refs.size == IDLE_ACTORS)
    return (after - before) / IDLE_ACTORS
}

private fun settledHeap(): Long {
    repeat(SETTLE_GCS) { System.gc() }
    val runtime = Runtime.getRuntime()
    return runtime.totalMemory() - runtime.freeMemory()
}
