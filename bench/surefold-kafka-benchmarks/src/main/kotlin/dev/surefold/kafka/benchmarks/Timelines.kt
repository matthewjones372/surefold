package dev.surefold.kafka.benchmarks

import org.apache.kafka.clients.consumer.ConsumerInterceptor
import org.apache.kafka.clients.consumer.ConsumerRecords
import org.apache.kafka.clients.consumer.OffsetAndMetadata
import org.apache.kafka.common.TopicPartition
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue

/** Poll returns and handler calls, stamped only while [Timelines] runs; the benchmarks leave [on] false. */
object Stamps {
    @JvmStatic var on = false

    val polls = ConcurrentLinkedQueue<LongArray>()

    val handled = ConcurrentLinkedQueue<LongArray>()

    fun handled(start: Long, end: Long) {
        handled += longArrayOf(start, end)
    }

    fun clear() {
        polls.clear()
        handled.clear()
    }
}

/** Called by the client as each poll returns, on the polling thread, with what it returned. */
class PollStamps : ConsumerInterceptor<Any?, Any?> {
    override fun onConsume(records: ConsumerRecords<Any?, Any?>): ConsumerRecords<Any?, Any?> {
        Stamps.polls += longArrayOf(System.nanoTime(), records.count().toLong())
        return records
    }

    override fun onCommit(offsets: Map<TopicPartition, OffsetAndMetadata>) = Unit

    override fun close() = Unit

    override fun configure(configs: Map<String, *>) = Unit
}

/** Each consumer run a few times with stamps on, the last run kept, written as JSON to the path given. */
fun main(args: Array<String>) {
    val broker = Broker().apply { start() }
    // On while the shared consumer is made, so its polls are stamped too.
    Stamps.on = true
    val comparison = ComparisonBenchmarks().apply { open(broker) }
    Stamps.on = false
    val scenarios = linkedMapOf<String, () -> Unit>(
        "kafkaClients" to { comparison.kafkaClients() },
        "kafkaClientsCommitting" to { comparison.kafkaClientsCommitting(broker) },
        "surefoldForks" to { comparison.surefoldForks(broker) },
        "surefoldPekko" to { comparison.surefoldPekko(broker) },
    )
    val processing = ProcessingBenchmarks().apply { ioMicros = TIMELINE_MICROS }
    scenarios["javaSerial@1000"] = { processing.javaSerial(broker) }
    scenarios["javaParallelBatch@1000"] = { processing.javaParallelBatch(broker) }
    scenarios["surefoldSerial@1000"] = { processing.surefoldSerial(broker) }
    scenarios["surefoldMapPar@1000"] = { processing.surefoldMapPar(broker) }

    val json = StringBuilder("{")
    scenarios.entries.forEachIndexed { i, (name, run) ->
        repeat(WARM_RUNS) { run() }
        Stamps.clear()
        Stamps.on = true
        val start = System.nanoTime()
        run()
        val end = System.nanoTime()
        Stamps.on = false
        fun us(t: Long) = (t - start) / NANOS_PER_MICRO
        if (i > 0) json.append(',')
        json.append("\"$name\":{\"totalUs\":${us(end)},\"polls\":[")
        json.append(Stamps.polls.joinToString(",") { "[${us(it[0])},${it[1]}]" })
        json.append("],\"handled\":[")
        json.append(Stamps.handled.joinToString(",") { "[${us(it[0])},${us(it[1])}]" })
        json.append("]}")
        println("$name: ${us(end) / MICROS_PER_MILLI} ms, ${Stamps.polls.size} polls")
    }
    json.append("}")
    File(args[0]).writeText(json.toString())
    comparison.close()
    broker.stop()
}

private const val WARM_RUNS = 3
private const val TIMELINE_MICROS = 1000
private const val NANOS_PER_MICRO = 1_000L
private const val MICROS_PER_MILLI = 1_000L
