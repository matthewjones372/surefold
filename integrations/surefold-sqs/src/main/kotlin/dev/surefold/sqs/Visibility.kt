package dev.surefold.sqs

import dev.surefold.fail.Either
import dev.surefold.fail.onLeft
import dev.surefold.logWarn
import software.amazon.awssdk.core.exception.SdkException
import software.amazon.awssdk.services.sqs.SqsClient
import software.amazon.awssdk.services.sqs.model.ChangeMessageVisibilityBatchRequestEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

/**
 * One run's renewals: every half [visibility], each message [hold] was told of and [release] was not is hidden for
 * [visibility] again. On a thread of its own, since the reader may be blocked in a receive, or not asked at all.
 */
internal class Extender(
    private val sqs: SqsClient,
    private val queue: Queue,
    private val visibility: Duration,
    private val meters: ConsumerMeters,
) {
    private val held: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private val stopped = CountDownLatch(1)

    private val thread = Thread.ofVirtual().name("surefold-sqs-visibility").start(::run)

    fun hold(handle: String) {
        held += handle
    }

    fun release(handle: String) {
        held -= handle
    }

    /** Returns once the thread has ended, after the renewal it may be in the middle of. */
    fun stop() {
        stopped.countDown()
        thread.join()
    }

    private fun run() {
        while (!stopped.await((visibility / 2).inWholeMilliseconds, TimeUnit.MILLISECONDS)) extend(held.toList())
    }

    // A renewal SQS fails is a message seen again after its timeout, not a broken run; the next round tries again.
    private fun extend(handles: List<String>) = handles.chunked(MAX_BATCH).forEach { chunk ->
        val entries = chunk.mapIndexed { id, handle ->
            ChangeMessageVisibilityBatchRequestEntry.builder()
                .id(id.toString())
                .receiptHandle(handle)
                .visibilityTimeout(visibility.inWholeSeconds.toInt())
                .build()
        }
        Either.catchOrThrow<SdkException, Unit> {
            val response = sqs.changeMessageVisibilityBatch { it.queueUrl(queue.url).entries(entries) }
            meters.extended(response.successful().size)
            response.failed().forEach { meters.extendFailed(it.code()) }
        }.onLeft { failed ->
            meters.extendFailed(failed.javaClass.simpleName)
            logWarn("surefold-sqs: extending visibility on ${queue.url} failed: $failed")
        }
    }
}
