package dev.surefold.sqs

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.metricTags
import dev.surefold.metrics
import software.amazon.awssdk.services.sqs.model.Message
import java.util.concurrent.atomic.LongAdder

// A duration is recorded in seconds, as surefold's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * A reader's own meters, `surefold.sqs.consumer.*` (spec 0289), tagged with its queue's name and the caller's tags.
 * Taken where the read is described, since a backend may open it on a thread that inherited neither.
 */
internal class ConsumerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {
    private val received: Counter = metrics.counter("$PREFIX.received", tags)
    private val bytes: Counter = metrics.counter("$PREFIX.bytes", tags)
    private val receive: Histogram = metrics.histogram("$PREFIX.receive", tags)
    private val deleted: Counter = metrics.counter("$PREFIX.deleted", tags)
    private val inFlight: Gauge = metrics.gauge("$PREFIX.in.flight", tags)
    private val handed = LongAdder()
    private val settled = LongAdder()

    fun received(messages: List<Message>, started: Long) {
        receive.record((System.nanoTime() - started) / NANOS_PER_SECOND)
        received.increment(messages.size.toDouble())
        bytes.increment(messages.sumOf { it.body().toByteArray().size }.toDouble())
    }

    fun handed() {
        handed.increment()
        inFlight()
    }

    fun settled() {
        settled.increment()
        inFlight()
    }

    fun deleted(count: Int) = deleted.increment(count.toDouble())

    fun deleteFailed(code: String) = metrics.counter("$PREFIX.delete.failures", tags + ("code" to code)).increment(1.0)

    private fun inFlight() = inFlight.set((handed.sum() - settled.sum()).toDouble())

    companion object {
        private const val PREFIX = "surefold.sqs.consumer"

        /** The metrics and tags in scope here, and [queue]'s name. */
        fun here(queue: Queue): ConsumerMeters = ConsumerMeters(
            metrics.get(),
            metricTags.get() + ("queue" to queue.name),
        )
    }
}
