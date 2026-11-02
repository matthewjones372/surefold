package dev.surefold.kafka.benchmarks

import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import java.time.Duration
import java.util.Locale

private const val RUNS = 15
private const val WARM_RUNS = 5
private const val SHORT_FETCH_WAIT_MILLIS = 50
private const val NANOS_PER_MILLI = 1e6
private const val SLEEP_MICROS = 1000
private val POLL_TIMEOUT: Duration = Duration.ofMillis(100)

private enum class Committing { NONE, SYNC, ASYNC }

/** Where a fresh consumer's run spends its time: the join, the polls, the last commit, and the close. */
fun main() {
    val broker = Broker().apply { start() }
    val shortWait = mapOf<String, Any>(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG to SHORT_FETCH_WAIT_MILLIS)
    broker.timed("no commits", Committing.NONE)
    broker.timed("commitSync before each poll", Committing.SYNC)
    broker.timed("commitAsync before each poll", Committing.ASYNC)
    broker.timed("no commits, fetch.max.wait.ms=50", Committing.NONE, shortWait)
    broker.timed("commitSync, fetch.max.wait.ms=50", Committing.SYNC, shortWait)
    // The serial handler on a platform thread and on a virtual thread: what Forks' thread costs a blocking call.
    for (virtual in listOf(false, true)) {
        val took = List(WARM_RUNS + 1) {
            var nanos = 0L
            val body = Runnable {
                val start = System.nanoTime()
                repeat(SLOW_RECORDS) { handle(SLEEP_MICROS) }
                nanos = System.nanoTime() - start
            }
            (if (virtual) Thread.ofVirtual() else Thread.ofPlatform()).start(body).join()
            nanos / NANOS_PER_MILLI
        }.drop(WARM_RUNS)
        val thread = if (virtual) "virtual thread" else "platform thread"
        println("%-34s %6.1f ms for 5k sleeps of 1 ms".format(Locale.ROOT, thread, took.average()))
    }
    broker.stop()
}

private fun Broker.timed(label: String, committing: Committing, extra: Map<String, Any> = emptyMap()) {
    val runs = List(RUNS) {
        val start = System.nanoTime()
        val consumer = KafkaConsumer(consumer() + extra, ByteArrayDeserializer(), ByteArrayDeserializer())
        consumer.subscribe(listOf(TOPIC))
        var count = 0L
        var first = 0L
        while (count < RECORDS) {
            when (committing) {
                Committing.SYNC -> consumer.commitSync()
                Committing.ASYNC -> consumer.commitAsync()
                Committing.NONE -> Unit
            }
            val polled = consumer.poll(POLL_TIMEOUT).count()
            if (polled > 0 && first == 0L) first = System.nanoTime()
            count += polled
        }
        val polled = System.nanoTime()
        if (committing != Committing.NONE) consumer.commitSync()
        val committed = System.nanoTime()
        consumer.close()
        val closed = System.nanoTime()
        listOf(first - start, polled - first, committed - polled, closed - committed, closed - start)
    }.drop(WARM_RUNS)
    val averages = List(runs.first().size) { i -> runs.map { it[i] }.average() / NANOS_PER_MILLI }
    val (join, polls, commit, close, total) = averages
    println(
        "%-34s join %6.1f  polls %6.1f  final commit %5.1f  close %6.1f  total %6.1f ms"
            .format(Locale.ROOT, label, join, polls, commit, close, total),
    )
}
