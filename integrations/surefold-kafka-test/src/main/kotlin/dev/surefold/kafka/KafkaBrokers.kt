package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.resource

/**
 * A [KafkaBroker], started when it is acquired and stopped on release: the shared resource of a surefold-junit suite.
 */
fun kafkaBroker(): Resource<KafkaBroker> = resource {
    install({ KafkaBroker().also { it.start() } }) { broker, _ -> broker.stop() }
}
