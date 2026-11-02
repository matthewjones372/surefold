package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.junit.SharedSuite
import dev.surefold.junit.shouldBe
import org.apache.kafka.clients.consumer.ConsumerConfig

object PartitionsSuite : SharedSuite<KafkaBroker>() {
    override val shared: Resource<KafkaBroker> = kafkaBroker()

    init {
        test("a topic's partitions are listed in order, by a consumer that joins no group") { kafka ->
            kafka.create(Topic("clicks"), partitions = 3)

            val bootstrap = mapOf<String, Any>(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG to kafka.bootstrap)

            Kafka.partitions(bootstrap, Topic("clicks")) shouldBe listOf(0, 1, 2)
        }
    }
}
