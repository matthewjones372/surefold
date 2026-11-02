package dev.surefold.kafka

import dev.surefold.assert.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension

/** The broker on plain Jupiter: registered as an extension, started before the class's tests (spec 0161). */
class KafkaBrokerExtensionTest {

    companion object {
        @JvmField
        @RegisterExtension
        val kafka = KafkaBroker()
    }

    @Test
    fun `a registered broker is running by the first test`() {
        val topic = Topic("registered")
        kafka.create(topic, partitions = 1)
        kafka.send(topic, "a", "b")
        kafka.read(topic, 2).map { it.value() } shouldBe listOf("a", "b")
    }
}
