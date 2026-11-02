package io.github.matthewjones372.surefold.cluster

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.DoubleAdder

/** One node's metrics, kept by name and tags. */
internal class NodeMetrics : Metrics {
    private val counters = ConcurrentHashMap<Pair<String, Map<String, String>>, DoubleAdder>()
    private val gauges = ConcurrentHashMap<Pair<String, Map<String, String>>, Double>()

    override fun counter(name: String, tags: Map<String, String>): Counter {
        val adder = counters.computeIfAbsent(name to tags) { DoubleAdder() }
        return Counter { adder.add(it) }
    }

    override fun gauge(name: String, tags: Map<String, String>): Gauge = Gauge { gauges[name to tags] = it }

    override fun histogram(name: String, tags: Map<String, String>): Histogram = Histogram { }

    fun counter(name: String, vararg tags: Pair<String, String>): Double = counters[name to tags.toMap()]?.sum() ?: 0.0

    fun gauge(name: String, vararg tags: Pair<String, String>): Double? = gauges[name to tags.toMap()]
}
