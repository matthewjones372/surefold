package dev.surefold.sns

import dev.surefold.Metrics
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.metricTags
import dev.surefold.metrics

// A duration is recorded in seconds, as surefold's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Publishes' own meters, `surefold.sns.publisher.*` (spec 0292), tagged with each message's topic and the caller's
 * tags: taken where a publish is described, so a stream's publishes record where it was described.
 */
internal class PublisherMeters(private val metrics: Metrics, private val tags: Map<String, String>) {

    /** One request to [topic], begun at [started], and the outcome of each message in it. */
    fun published(topic: SnsTopic, started: Long, outcomes: List<Either<PublishFailed, Published>>) {
        val of = tags + ("topic" to topic.arn.substringAfterLast(':'))
        metrics.histogram("$PREFIX.publish", of).record((System.nanoTime() - started) / NANOS_PER_SECOND)
        outcomes.forEach { outcome ->
            outcome.fold(
                { failed ->
                    val code = (failed.cause as? SnsRefused)?.code ?: failed.cause.javaClass.simpleName
                    metrics.counter("$PREFIX.failures", of + ("code" to code)).increment(1.0)
                },
                { metrics.counter("$PREFIX.published", of).increment(1.0) },
            )
        }
    }

    companion object {
        private const val PREFIX = "surefold.sns.publisher"

        /** The metrics and tags in scope here. */
        fun here(): PublisherMeters = PublisherMeters(metrics.get(), metricTags.get())
    }
}
