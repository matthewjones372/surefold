package dev.surefold.actor.benchmarks

import com.typesafe.config.ConfigFactory
import dev.surefold.actor.ask
import dev.surefold.actor.spawn
import dev.surefold.scope
import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.actor.typed.javadsl.Adapter
import org.apache.pekko.actor.typed.javadsl.AskPattern
import java.lang.management.ManagementFactory
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.LongAdder
import java.util.concurrent.locks.LockSupport
import kotlin.time.toKotlinDuration
import com.sun.management.OperatingSystemMXBean as ProcessCpu
import org.apache.pekko.actor.typed.ActorRef as PekkoRef

private const val ASKERS = 64
private const val PACE_PER_SECOND = 50_000L
private const val ROUND_NANOS = 2_000_000_000L
private const val ROUNDS = 8
private const val NANOS_PER_MICRO = 1_000.0

/** One way of asking one actor, as each asker does it once. */
private class Asking(val name: String, val ask: () -> Unit)

/**
 * Process CPU per ask (spec 0144), which JMH cannot show: a paced run takes as long as its pace, whatever it costs.
 * [ASKERS] virtual threads ask one actor, in a closed loop and paced together to [PACE_PER_SECOND] asks a second,
 * on surefold and on Pekko. Each row is the median of [ROUNDS] rounds of two seconds, the four interleaved round by
 * round so a load that comes and goes falls on each alike. Run it with `./gradlew :surefold-actor-benchmarks:askCpu`.
 */
fun main() {
    val pekkoSystem = ActorSystem.create("ask-cpu", ConfigFactory.load())
    try {
        scope {
            val surefold = spawn("answerer", surefoldAnswerer(), capacity = CAPACITY)
            val pekko = Adapter.spawn(pekkoSystem, pekkoAnswerer(), "answerer")
            val scheduler = Adapter.toTyped(pekkoSystem).scheduler()
            val within = ASK_WITHIN.toKotlinDuration()
            val askings = listOf(
                Asking("surefold") {
                    checkNotNull(surefold.ask<SurefoldQuestion, Int>(within) { SurefoldQuestion(1, it) }.getOrNull())
                },
                Asking("pekko") {
                    AskPattern.ask(pekko, { reply: PekkoRef<Int> -> PekkoQuestion(1, reply) }, ASK_WITHIN, scheduler)
                        .toCompletableFuture()
                        .join()
                },
            )
            val runs = askings.flatMap { asking -> listOf(asking to false, asking to true) }

            // A first round of each, unmeasured, so the JIT has compiled what the measured rounds run.
            runs.forEach { (asking, paced) -> round(asking, paced) }
            val measured = runs.associateWith { mutableListOf<Double>() }
            repeat(ROUNDS) { runs.forEach { run -> measured.getValue(run) += round(run.first, run.second) } }

            val seconds = TimeUnit.NANOSECONDS.toSeconds(ROUND_NANOS)
            println("CPU per ask, median of $ROUNDS rounds of $seconds s, $ASKERS askers")
            measured.forEach { (run, perAsk) ->
                val pace = if (run.second) "paced to $PACE_PER_SECOND/s" else "closed loop"
                println("%-9s %-18s %6.1f us".format(Locale.ROOT, run.first.name, pace, perAsk.sorted()[ROUNDS / 2]))
            }
        }
    } finally {
        pekkoSystem.terminate()
        pekkoSystem.getWhenTerminated().toCompletableFuture().join()
    }
}

/** One round of [asking], paced or not: the process CPU it took, in microseconds per ask. */
private fun round(asking: Asking, paced: Boolean): Double {
    val cpu = ManagementFactory.getOperatingSystemMXBean() as ProcessCpu
    val asked = LongAdder()
    val gapNanos = TimeUnit.SECONDS.toNanos(1) * ASKERS / PACE_PER_SECOND
    val cpuBefore = cpu.processCpuTime
    val start = System.nanoTime()
    val end = start + ROUND_NANOS
    val askers = List(ASKERS) { i ->
        Thread.ofVirtual().start {
            // Each asker starts at its own offset, so the paced asks arrive spread out rather than in a burst.
            var next = start + gapNanos * i / ASKERS
            while (true) {
                if (paced) {
                    val wait = next - System.nanoTime()
                    if (wait > 0) LockSupport.parkNanos(wait)
                    next += gapNanos
                }
                if (System.nanoTime() >= end) break
                asking.ask()
                asked.increment()
            }
        }
    }
    askers.forEach(Thread::join)
    return (cpu.processCpuTime - cpuBefore) / NANOS_PER_MICRO / asked.sum()
}
