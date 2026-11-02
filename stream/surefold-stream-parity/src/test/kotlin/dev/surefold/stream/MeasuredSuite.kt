package dev.surefold.stream

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldBeGreaterThan
import dev.surefold.junit.shouldBeInstanceOf
import dev.surefold.junit.shouldContainExactly
import dev.surefold.junit.withClue
import org.apache.pekko.actor.ActorSystem
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.DoubleAdder

/** A measured run on every backend: each stage's elements counted, and the slow one's body the busiest. */
object MeasuredSuite : SharedSuite<ActorSystem>() {
    override val shared: Resource<ActorSystem> = actorSystem("surefold-stream-measured-test")

    // Well above a loaded runner's scheduling noise, or the stage is not the slow one it is named for.
    private const val SLOW_MILLIS = 25L

    // What a stage records a duration in: seconds, as `timed` does.
    private const val SLOW_SECONDS = SLOW_MILLIS / 1_000.0
    private const val SETTLE_SECONDS = 10L

    private val backends: List<Backend> = listOf(OnPekko, OnForks)

    /** Every instrument, kept by stage and step so a test can read them back. */
    private class Recorded : Metrics {
        val counts = ConcurrentHashMap<String, DoubleAdder>()
        val samples = ConcurrentHashMap<String, ConcurrentLinkedQueue<Double>>()

        private fun key(tags: Map<String, String>) = "${tags["step"]}:${tags["stage"]}"

        override fun counter(name: String, tags: Map<String, String>): Counter {
            val total = counts.computeIfAbsent(key(tags)) { DoubleAdder() }
            return Counter { by -> total.add(by) }
        }

        override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { }

        override fun histogram(name: String, tags: Map<String, String>): Histogram {
            val metric = name.substringAfterLast('.')
            val kept = samples.computeIfAbsent("$metric|${key(tags)}") { ConcurrentLinkedQueue() }
            return Histogram { value -> kept.add(value) }
        }

        /** The mean of one histogram, by metric (`busy` or `waiting`) and `step:stage`. */
        fun mean(metric: String, stage: String): Double = samples.getValue("$metric|$stage").average()

        fun stages(metric: String): Set<String> =
            samples.keys.filter { it.startsWith("$metric|") }.map { it.substringAfter('|') }.toSet()

        fun busiest(): String = stages("busy").maxBy { mean("busy", it) }
    }

    private val pipeline: Run<Nothing, List<Int>> =
        Stream.from(1..20)
            .map { it + 1 }
            .map { n ->
                Thread.sleep(SLOW_MILLIS)
                n
            }
            .filter { it % 2 == 0 }
            .take(100)
            .runCollect()

    private val slowInItsOwnStage: Run<Nothing, List<Int>> =
        Stream.from(1..20)
            .map { it + 1 }
            .map { it * 1 }
            .mapConcat { n ->
                Thread.sleep(SLOW_MILLIS)
                listOf(n)
            }
            .drop(0)
            .runCollect()

    init {
        backends.forEach { backend ->
            test("the slow stage is the busiest, and every stage counts what it emitted [${backend.name}]") { system ->
                val recorded = Recorded()
                val measured = pipeline.measured(Measured("orders", recorded, sampleEvery = 1))

                val exit = measured.run(backend.on(system)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)

                exit.shouldBeInstanceOf<Exit.Done<List<Int>>> { it.value.size shouldBe 10 }
                withClue("the map that sleeps is step 2, and it is where the time went") {
                    recorded.busiest() shouldBe "2:map"
                    recorded.mean("busy", "2:map") shouldBeGreaterThan SLOW_SECONDS * 0.9
                }
                withClue("a stage with no body of its own to time reports no busy, not an empty series") {
                    recorded.stages("busy") shouldBe setOf("1:map", "2:map", "3:filter")
                }
                recorded.counts.mapValues { it.value.sum().toInt() } shouldBe mapOf(
                    "0:Stream.from" to 20,
                    "1:map" to 20,
                    "2:map" to 20,
                    "3:filter" to 10,
                    "4:take" to 10,
                )
            }
        }

        backends.forEach { backend ->
            test(
                "stages upstream of the slow one wait for demand, and waiting drops at the slow one [${backend.name}]",
            ) { system ->
                val recorded = Recorded()
                val measured = slowInItsOwnStage.measured(Measured("orders", recorded, sampleEvery = 1))

                measured.run(backend.on(system)).toCompletableFuture().get(SETTLE_SECONDS, TimeUnit.SECONDS)
                    .shouldBeInstanceOf<Exit.Done<List<Int>>> { _ ->
                        // The source, the fused run of maps ending at step 2, the slow mapConcat, and the drop
                        // after it.
                        recorded.stages("waiting") shouldBe
                            setOf("0:Stream.from", "2:map", "3:mapConcat", "4:drop")
                        val upstream = listOf("0:Stream.from", "2:map").map { recorded.mean("waiting", it) }
                        val atAndAfter = listOf("3:mapConcat", "4:drop").map { recorded.mean("waiting", it) }
                        withClue("waiting upstream $upstream, at and after the slow stage $atAndAfter") {
                            upstream.min() shouldBeGreaterThan SLOW_SECONDS * 0.9
                            upstream.min() shouldBeGreaterThan atAndAfter.max() * 10
                        }
                    }
            }
        }

        test("a measured run fuses as an unmeasured one does, and each step keeps its own numbers") { _ ->
            val compiled = pipeline.measured(Measured("orders", Recorded())).node.optimised()

            compiled.shouldBeInstanceOf<Node.Probed> { probed ->
                probed.upstream.shouldBeInstanceOf<Node.Take> { take ->
                    take.upstream.shouldBeInstanceOf<Node.Probed> { inner ->
                        inner.upstream.shouldBeInstanceOf<Node.Fused> { fused ->
                            fused.steps.map { it.operator } shouldContainExactly listOf("map", "map", "filter")
                        }
                    }
                }
            }
        }
    }
}
