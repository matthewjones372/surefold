package dev.surefold.sqs

import dev.surefold.Counter
import dev.surefold.Gauge
import dev.surefold.Histogram
import dev.surefold.Metrics
import dev.surefold.fail.Either
import dev.surefold.fail.fold
import dev.surefold.metricTags
import dev.surefold.metrics
import software.amazon.awssdk.services.sqs.model.Message
import java.util.concurrent.atomic.LongAdder

// A duration is recorded in seconds, as surefold's `timed` records it and a backend assumes a duration is.
private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0

/** One of SQS's own attributes on [this], as a number, where the receive asked for it. */
internal fun Message.attribute(name: String): Long? = attributesAsStrings()[name]?.toLongOrNull()

/**
 * A reader's own meters, `surefold.sqs.consumer.*` (spec 0289), tagged with its queue's name and the caller's tags.
 * Taken where the read is described, since a backend may open it on a thread that inherited neither.
 */
internal class ConsumerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {
    private val received: Counter = metrics.counter("$PREFIX.received", tags)
    private val bytes: Counter = metrics.counter("$PREFIX.bytes", tags)
    private val receive: Histogram = metrics.histogram("$PREFIX.receive", tags)
    private val deleted: Counter = metrics.counter("$PREFIX.deleted", tags)
    private val redelivered: Counter = metrics.counter("$PREFIX.redelivered", tags)
    private val latency: Histogram = metrics.histogram("$PREFIX.latency", tags)
    private val extended: Counter = metrics.counter("$PREFIX.extended", tags)
    private val nacks: Counter = metrics.counter("$PREFIX.nacks", tags)
    private val inFlight: Gauge = metrics.gauge("$PREFIX.in.flight", tags)
    private val handed = LongAdder()
    private val settled = LongAdder()

    fun received(messages: List<Message>, started: Long) {
        receive.record((System.nanoTime() - started) / NANOS_PER_SECOND)
        received.increment(messages.size.toDouble())
        bytes.increment(messages.sumOf { it.body().toByteArray().size }.toDouble())
        redelivered.increment(messages.count { (it.attribute(RECEIVE_COUNT) ?: 1L) > 1L }.toDouble())
    }

    /** From SQS's clock to this one, so skew between them shows up here. */
    fun handled(sentMillis: Long) = latency.record((System.currentTimeMillis() - sentMillis) / MILLIS_PER_SECOND)

    fun extended(count: Int) = extended.increment(count.toDouble())

    fun extendFailed(code: String) = metrics.counter("$PREFIX.extend.failures", tags + ("code" to code)).increment(1.0)

    fun nacked() = nacks.increment(1.0)

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
        private const val RECEIVE_COUNT = "ApproximateReceiveCount"

        /** The metrics and tags in scope here, and [queue]'s name. */
        fun here(queue: Queue): ConsumerMeters = ConsumerMeters(
            metrics.get(),
            metricTags.get() + ("queue" to queue.name),
        )
    }
}

/**
 * Sends' own meters, `surefold.sqs.producer.*` (spec 0289), tagged with each message's queue and the caller's tags:
 * taken where a send is described, so a stream's sends record where it was described rather than where they run.
 */
internal class ProducerMeters(private val metrics: Metrics, private val tags: Map<String, String>) {

    /** One request to [queue], begun at [started], and the outcome of each message in it. */
    fun sent(queue: Queue, started: Long, outcomes: List<Either<SendFailed, Sent>>) {
        val of = tags + ("queue" to queue.name)
        metrics.histogram("$PREFIX.send", of).record((System.nanoTime() - started) / NANOS_PER_SECOND)
        outcomes.forEach { outcome ->
            outcome.fold(
                { failed ->
                    val code = (failed.cause as? SqsRefused)?.code ?: failed.cause.javaClass.simpleName
                    metrics.counter("$PREFIX.failures", of + ("code" to code)).increment(1.0)
                },
                { metrics.counter("$PREFIX.sent", of).increment(1.0) },
            )
        }
    }

    companion object {
        private const val PREFIX = "surefold.sqs.producer"

        /** The metrics and tags in scope here. */
        fun here(): ProducerMeters = ProducerMeters(metrics.get(), metricTags.get())
    }
}
