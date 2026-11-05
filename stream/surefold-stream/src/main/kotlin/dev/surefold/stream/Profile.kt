package dev.surefold.stream

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.NoMetrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder
import java.util.concurrent.atomic.LongAdder

/** What one stage of a measured run did. `busy` and `waiting` are means in milliseconds, where it reported them. */
data class StageProfile(
    val elements: Long,
    val busyMillis: Double? = null,
    val waitingMillis: Double? = null,
    /** The sampled busy time in all, which a stage's share of the run is worked out from. */
    val busySampledMillis: Double = 0.0,
    /** The operator that reported it, so a profile can be matched against the run it is rendered with (spec 0148). */
    val stage: String? = null,
    /** Where that operator was written, when the compiler plugin saw it. */
    val at: String? = null,
)

/** A measured run's stages, keyed by `step`: each stage's place in the order data moves through the description. */
class Profile(val stages: Map<Int, StageProfile>) {

    private val busyInAll = stages.values.sumOf { it.busySampledMillis }

    /**
     * The stage's part of the time every stage spent in its own body. Every stage samples one element in the same
     * number, so sampled time is in proportion to the time itself. Null for a stage with no body.
     */
    fun share(step: Int): Double? =
        stages[step]?.takeIf { it.busyMillis != null && busyInAll > 0 }?.let { it.busySampledMillis / busyInAll }
}

/**
 * [Metrics] that keeps what a measured run reports, and hands it on to [also]: `Measured(name, profiler)`, run it, then
 * [profile] and `render(profile = …)` to see where the time went.
 */
class Profiler(private val also: Metrics = NoMetrics) : Metrics {

    private class Tally {
        val sum = DoubleAdder()
        val count = LongAdder()

        fun add(value: Double) {
            sum.add(value)
            count.increment()
        }

        fun mean(): Double? = count.sum().takeIf { it > 0 }?.let { sum.sum() / it }
    }

    /** One stage of one pipeline: two pipelines measured by one profiler are kept apart (spec 0148). */
    private data class Step(val pipeline: String, val step: Int)

    private class Stage(val operator: String?, val at: String?)

    private val tallies = ConcurrentHashMap<Pair<String, Step>, Tally>()

    /** Which operator, written where, reported each step: what a rendered run is checked against. */
    private val stages = ConcurrentHashMap<Step, Stage>()

    private fun tally(name: String, tags: Map<String, String>): Tally? =
        tags["step"]?.toIntOrNull()?.let { number ->
            val step = Step(tags["pipeline"].orEmpty(), number)
            stages.computeIfAbsent(step) { Stage(tags["stage"], tags["at"]?.takeUnless { site -> site == NO_SITE }) }
            tallies.computeIfAbsent(name to step) { Tally() }
        }

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val kept = tally(name, tags)
        val passed = also.counter(name, tags)
        return Counter { by ->
            kept?.sum?.add(by)
            passed.increment(by)
        }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = also.gauge(name, tags)

    override fun histogram(name: String, tags: Map<String, String>): Histogram {
        val kept = tally(name, tags)
        val passed = also.histogram(name, tags)
        return Histogram { value ->
            kept?.add(value)
            passed.record(value)
        }
    }

    /**
     * What every stage of [pipeline] reported so far. Without one, the only pipeline this profiler has seen: with
     * several, it refuses rather than sum them, and names them.
     */
    fun profile(pipeline: String? = null): Profile {
        val chosen =
            pipeline
                ?: stages.keys
                    .map { it.pipeline }
                    .toSortedSet()
                    .let { seen ->
                        check(seen.size <= 1) { "this profiler saw ${seen.joinToString(" and ")}; say which" }
                        seen.firstOrNull()
                    }
        val steps = stages.filterKeys { it.pipeline == chosen }
        val of = { name: String, step: Step -> tallies[name to step] }
        return Profile(
            steps.entries.associate { (step, stage) ->
                step.step to
                    StageProfile(
                        elements = of(ELEMENTS, step)?.sum?.sum()?.toLong() ?: 0,
                        busyMillis = of(BUSY, step)?.mean()?.times(MILLIS_PER_SECOND),
                        waitingMillis = of(WAITING, step)?.mean()?.times(MILLIS_PER_SECOND),
                        busySampledMillis = (of(BUSY, step)?.sum?.sum() ?: 0.0) * MILLIS_PER_SECOND,
                        stage = stage.operator,
                        at = stage.at,
                    )
            },
        )
    }

    private companion object {
        const val ELEMENTS = "surefold.stream.elements"
        const val BUSY = "surefold.stream.busy"
        const val WAITING = "surefold.stream.waiting"

        /** The `at` tag of a stage the compiler plugin did not see. */
        const val NO_SITE = "-"

        // A stage records its times in seconds; a profile reads them in milliseconds.
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
