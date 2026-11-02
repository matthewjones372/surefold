package dev.surefold.kafka

import dev.surefold.capturingLogs
import dev.surefold.junit.Suite
import dev.surefold.junit.shouldBe
import dev.surefold.junit.shouldContain
import dev.surefold.junit.shouldNotContainKey
import dev.surefold.logInfo
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.common.record.TimestampType
import java.util.Optional

/** A record as a consumer would be handed it, with [produced]'s headers. */
private fun consumed(produced: ProducerRecord<String, String>) = ConsumerRecord(
    produced.topic(), 0, 0L, 0L, TimestampType.CREATE_TIME, 0, 0, produced.key(), produced.value(),
    produced.headers(), Optional.empty(),
)

/** The annotations on a line written inside [block]'s handling. */
private fun annotationsInside(record: ConsumerRecord<String, String>): Map<String, String> = record.within {
    capturingLogs { logs ->
        logInfo("handled")
        logs.all().single().annotations
    }
}

/** Spec 0123: what an event carried rides its record as headers, and the consumer handles it inside them. */
object CarriedSuite : Suite() {
    init {
        test("a record carrying a map has it as headers, read back as the same map") {
            val carried = mapOf("traceparent" to "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01", "k" to "é")
            val record = ProducerRecord("orders", "o-1", "placed").carrying(carried)

            consumed(record).carried() shouldBe carried
        }

        test("a record is handled inside the annotations it carried, and one that carried none outside any") {
            val carried =
                ProducerRecord("orders", "o-1", "placed").carrying(mapOf("surefold.annotation.request_id" to "r-3"))
            val bare = ProducerRecord("orders", "o-2", "placed")

            annotationsInside(consumed(carried)) shouldContain ("request_id" to "r-3")
            annotationsInside(consumed(bare)) shouldNotContainKey "request_id"
        }
    }
}
