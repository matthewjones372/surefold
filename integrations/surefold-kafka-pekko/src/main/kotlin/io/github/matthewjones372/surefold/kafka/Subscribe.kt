@file:OptIn(KafkaSpi::class, SourceSeam::class)

package io.github.matthewjones372.surefold.kafka

import dev.surefold.fail.Either
import io.github.matthewjones372.surefold.stream.Run
import io.github.matthewjones372.surefold.stream.SourceSeam
import io.github.matthewjones372.surefold.stream.Stream
import io.github.matthewjones372.surefold.stream.hooked
import io.github.matthewjones372.surefold.stream.mapConcat
import io.github.matthewjones372.surefold.stream.runWith
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.pekko.Done
import org.apache.pekko.kafka.CommitterSettings
import org.apache.pekko.kafka.ConsumerMessage
import org.apache.pekko.kafka.ConsumerSettings
import org.apache.pekko.kafka.Subscriptions
import org.apache.pekko.kafka.javadsl.Committer
import org.apache.pekko.kafka.javadsl.Consumer

/**
 * Every record on [topics] for the settings' group, through Pekko's Kafka connector, each with the offset
 * [runCommitting] commits. Pekko runs it and nothing else does; [Kafka.consume] runs on any backend.
 *
 * A run's `stop()` drains: the consumer stops fetching, and the records it already sent finish and are
 * committed before the exit completes. The consumer is shut down once the run has ended.
 */
fun <K, V> Kafka.subscribe(
    settings: ConsumerSettings<K, V>,
    vararg topics: Topic,
): Stream<Nothing, Committed<ConsumerRecord<K, V>>> =
    Stream.hooked { hooks ->
        Consumer.committableSource(settings, Subscriptions.topics(topics.map { it.name }.toSet()))
            .mapMaterializedValue { control ->
                hooks.onStop { control.stop() }
                hooks.onEnd { control.shutdown() }
            }
            .map { message ->
                val record = message.record()
                Committed(
                    record,
                    Position(record.topic(), record.partition(), record.offset()),
                    Connector(message.committableOffset()),
                )
            }
    }

/**
 * [subscribe] over bytes, each key and value decoded in the stream: a record that cannot be read is a `Left`
 * that still carries its offset, so it is routed rather than ending the consumer.
 */
fun <K, V> Kafka.subscribe(
    settings: ConsumerSettings<ByteArray?, ByteArray?>,
    vararg topics: Topic,
    key: Decoder<K>,
    value: Decoder<V>,
): Stream<Nothing, Committed<Either<DecodeError, ConsumerRecord<K, V>>>> =
    subscribe(settings, *topics).decodedWith(key, value)

/**
 * A run described that commits each element's offset, or each of a batch's, through the connector once it reaches
 * the end.
 */
fun <E> Stream<E, Committed<*>>.runCommitting(settings: CommitterSettings): Run<E, Done> =
    mapConcat { element -> element.records().mapNotNull { it.handle?.committable() } }
        .runWith(Committer.sink<ConsumerMessage.Committable>(settings))

/** The connector's own offset, which its committer sink commits in batches. */
private class Connector(val offset: ConsumerMessage.Committable) : Handle {
    override fun handled() = error(ONE_CONSUMER_EACH)
}

private fun Handle.committable(): ConsumerMessage.Committable =
    (this as? Connector)?.offset ?: error(ONE_CONSUMER_EACH)

private const val ONE_CONSUMER_EACH =
    "records from Kafka.subscribe end on runCommitting(settings), and records from Kafka.consume on runCommitting()"
