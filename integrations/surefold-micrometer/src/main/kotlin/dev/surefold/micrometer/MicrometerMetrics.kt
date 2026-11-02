package dev.surefold.micrometer

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.Reading
import io.micrometer.core.instrument.DistributionSummary
import io.micrometer.core.instrument.MeterRegistry
import io.micrometer.core.instrument.Tag
import io.micrometer.core.instrument.Tags
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import io.micrometer.core.instrument.Counter as MicrometerCounter
import io.micrometer.core.instrument.Gauge as MicrometerGauge
import io.micrometer.core.instrument.Metrics as MicrometerRegistries

/**
 * surefold's measurements, handed to the `MeterRegistry` a service already has.
 *
 * ```kotlin
 * counter("petshop.adoptions").increment()   // nothing else; the module registers itself
 * ```
 *
 * [registry] defaults to Micrometer's own global, which is where a service on Spring or on the
 * OpenTelemetry bridge has already put one — so the setup is a dependency and nothing else. Bind a
 * different one with `metrics.locally(MicrometerMetrics(registry)) { … }`.
 *
 * Looking a meter up by name and tags is a map hit in Micrometer, which is what lets a call site ask
 * for one every time rather than hold it.
 */
class MicrometerMetrics(private val registry: MeterRegistry = MicrometerRegistries.globalRegistry) : Metrics {

    // Micrometer asks a gauge for its value rather than being told, so a `set` needs something to
    // hold the number and something registered to read it. One per name and tags, made once.
    private val held = ConcurrentHashMap<String, AtomicReference<Double>>()

    override fun counter(name: String, tags: Map<String, String>): Counter =
        registry.counter(name, tagsOf(tags)).let { counted -> Counter { by -> counted.increment(by) } }

    override fun gauge(name: String, tags: Map<String, String>): Gauge {
        val number = held.computeIfAbsent(keyOf(name, tags)) {
            AtomicReference(0.0).also { holder ->
                MicrometerGauge.builder(name) { holder.get() }.tags(tagsOf(tags)).register(registry)
            }
        }
        return Gauge { value -> number.set(value) }
    }

    override fun histogram(name: String, tags: Map<String, String>): Histogram =
        registry.summary(name, tagsOf(tags)).let { taken -> Histogram { value -> taken.record(value) } }

    /**
     * Every meter of [name] in the registry (spec 0109): a counter as a total, a gauge as its value, and a
     * distribution summary as its count, sum and whatever histogram buckets a meter filter gave it. Meters surefold
     * does not write, timers among them, are left out.
     */
    override fun read(name: String): List<Reading> = registry.find(name).meters().mapNotNull { meter ->
        val tags = meter.id.tags.associate { it.key to it.value }
        when (meter) {
            is MicrometerCounter -> Reading.Total(name, tags, meter.count())

            is MicrometerGauge -> Reading.Value(name, tags, meter.value())

            is DistributionSummary -> meter.takeSnapshot().let { taken ->
                Reading.Distribution(
                    name,
                    tags,
                    taken.count(),
                    taken.total(),
                    taken.histogramCounts().associate { it.bucket() to it.count() },
                )
            }

            else -> null
        }
    }

    private fun tagsOf(tags: Map<String, String>): Tags =
        Tags.of(tags.map { (key, value) -> Tag.of(key, value) })

    private fun keyOf(name: String, tags: Map<String, String>): String =
        name + tags.toSortedMap().entries.joinToString(",", prefix = "{", postfix = "}")
}
