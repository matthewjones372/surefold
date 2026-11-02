package dev.surefold

import java.util.ServiceLoader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

// What a duration is recorded in. Seconds, because every backend this reaches treats that as the
// unit of a duration: Prometheus names a series `_seconds`, and a dashboard told nothing assumes it.
private const val NANOS_PER_SECOND = 1_000_000_000.0

/** A number that only goes up. */
fun interface Counter {
    fun increment(by: Double)
}

/** [Counter.increment] by one, which is what nearly every call site wants. */
fun Counter.increment(): Unit = increment(1.0)

/** A number that goes up and down. */
fun interface Gauge {
    fun set(value: Double)
}

/** A distribution the backend, not this, decides how to summarise. */
fun interface Histogram {
    fun record(value: Double)
}

/**
 * Where a measurement goes, and the one thing an adapter implements.
 *
 * Instruments rather than measurements: a `Logger` takes a line per event because every line is
 * wanted, and a metric that handed over a value per increment would give a backend a million a
 * second to add up when holding one number is its whole job. Looking one up by name is a map hit
 * every backend already does, which is what lets a call site be `counter("…").increment()` with
 * nothing held and nothing cached here.
 */
interface Metrics {
    fun counter(name: String, tags: Map<String, String>): Counter

    fun gauge(name: String, tags: Map<String, String>): Gauge

    fun histogram(name: String, tags: Map<String, String>): Histogram

    /**
     * Every instrument named [name], one reading per set of its tags, as the backend holds it now (spec 0109); none
     * where it keeps nothing. The backend is the store: surefold keeps no copy to answer from.
     */
    fun read(name: String): List<Reading> = emptyList()
}

/** What one instrument holds now, under its name and one set of its tags (spec 0109). */
sealed interface Reading {
    val name: String
    val tags: Map<String, String>

    /** A counter's total. */
    data class Total(override val name: String, override val tags: Map<String, String>, val total: Double) : Reading

    /** A gauge's value. */
    data class Value(override val name: String, override val tags: Map<String, String>, val value: Double) : Reading

    /**
     * A histogram: how many values, their sum, and how many were at or below each bound where the backend keeps
     * buckets, else none. A quantile over an interval is the difference of two readings' buckets.
     */
    data class Distribution(
        override val name: String,
        override val tags: Map<String, String>,
        val count: Long,
        val sum: Double,
        val buckets: Map<Double, Double>,
    ) : Reading
}

/** Every instrument, doing nothing. A service with no adapter is not a service with a broken one. */
object NoMetrics : Metrics {
    override fun counter(name: String, tags: Map<String, String>): Counter = Counter { }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }
}

/** Where measurements go, and what a fork inherits from its opener. */
val metrics: SurefoldLocal<Metrics> = surefoldLocal { discoveredMetrics }

// Resolved once, for the reason `discovered` is in Logger.kt: the initial value of a SurefoldLocal is
// asked for on every unbound read, and a ServiceLoader scan per read is a scan per measurement.
private val discoveredMetrics: Metrics by lazy {
    firstRegistered("Metrics", NoMetrics) {
        ServiceLoader.load(Metrics::class.java, Metrics::class.java.classLoader).firstOrNull()
    }
}

/** The counter of this name, with whatever [metricTags] are bound and [tags] besides. */
fun counter(name: String, vararg tags: Pair<String, String>): Counter =
    metrics.get().counter(name, tagsOf(tags))

/** The gauge of this name. */
fun gauge(name: String, vararg tags: Pair<String, String>): Gauge =
    metrics.get().gauge(name, tagsOf(tags))

/** The histogram of this name. */
fun histogram(name: String, vararg tags: Pair<String, String>): Histogram =
    metrics.get().histogram(name, tagsOf(tags))

/** Every instrument of this name as the metrics in scope hold it now (spec 0109). */
fun readings(name: String): List<Reading> = metrics.get().read(name)

internal fun tagsOf(tags: Array<out Pair<String, String>>): Map<String, String> =
    metricTags.get() + tags

/**
 * The tags every measurement taken on this thread carries, and the ones a fork inherits.
 *
 * Deliberately not [annotations], which is the same propagation and the wrong source. A log
 * annotation is written to be unique — a correlation id, a pet's identifier — and a tag whose values
 * are unbounded is one time series per request, which is how a metrics backend dies. A tag's values
 * are few and known before the code runs.
 */
val metricTags: SurefoldLocal<Map<String, String>> = surefoldLocal { emptyMap() }

/** Runs [block] with [pairs] on every measurement taken inside it, this thread's forks included. */
fun <A> metricTagged(vararg pairs: Pair<String, String>, block: () -> A): A =
    metricTags.locally(metricTags.get() + pairs, block)

/**
 * Runs [block] and records how long it took, in seconds, under [name].
 *
 * Seconds rather than milliseconds because that is what a backend assumes a duration is: name the
 * metric for it, as in `petshop.adopt.duration.seconds`, and Prometheus, Grafana and every dashboard
 * agree about the axis without being told. Up to 0.9.2 it recorded milliseconds.
 *
 * The duration is recorded whether the block returns or throws: a call that fails slowly is the one
 * worth seeing, and leaving it out makes the numbers say the opposite.
 */
fun <A> timed(name: String, vararg tags: Pair<String, String>, block: () -> A): A {
    val startedAt = System.nanoTime()
    try {
        return block()
    } finally {
        histogram(name, *tags).record((System.nanoTime() - startedAt) / NANOS_PER_SECOND)
    }
}

/** What was measured inside a [capturingMetrics] block. */
class CapturedMetrics internal constructor() : Metrics {

    private val counters = ConcurrentHashMap<String, DoubleAdder>()
    private val gauges = ConcurrentHashMap<String, Double>()
    private val histograms = ConcurrentHashMap<String, MutableList<Double>>()
    private val labels = ConcurrentHashMap<String, Map<String, String>>()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        labels[name] = tags
        val adder = counters.computeIfAbsent(name) { DoubleAdder() }
        return Counter { by -> adder.add(by) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge {
        labels[name] = tags
        return Gauge { value -> gauges[name] = value }
    }

    override fun histogram(name: String, tags: Map<String, String>): Histogram {
        labels[name] = tags
        val taken = histograms.computeIfAbsent(name) { java.util.Collections.synchronizedList(mutableListOf()) }
        return Histogram { value -> taken.add(value) }
    }

    /** What [name] was incremented by in total, or zero where nothing touched it. */
    fun counter(name: String): Double = counters[name]?.sum() ?: 0.0

    /** What [name] was last set to, or null. */
    fun gauge(name: String): Double? = gauges[name]

    /** Every value [name] was given, in order. */
    fun histogram(name: String): List<Double> = histograms[name]?.let { synchronized(it) { it.toList() } }.orEmpty()

    /** The tags the last look-up of [name] carried, which is what a claim about scope reads. */
    fun tags(name: String): Map<String, String> = labels[name].orEmpty()

    override fun read(name: String): List<Reading> {
        val tags = tags(name)
        return listOfNotNull(
            counters[name]?.let { Reading.Total(name, tags, it.sum()) },
            gauges[name]?.let { Reading.Value(name, tags, it) },
            histograms[name]?.let { histogram(name) }?.let { taken ->
                Reading.Distribution(name, tags, taken.size.toLong(), taken.sum(), emptyMap())
            },
        )
    }
}

/** Binds metrics a test can read, rather than a backend nobody can assert on. */
fun <A> capturingMetrics(block: (CapturedMetrics) -> A): A {
    val captured = CapturedMetrics()
    return metrics.locally(captured) { block(captured) }
}
