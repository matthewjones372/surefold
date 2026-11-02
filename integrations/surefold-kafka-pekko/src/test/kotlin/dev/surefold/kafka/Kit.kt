package dev.surefold.kafka

import dev.surefold.Resource
import dev.surefold.resource
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit

/**
 * A broker, and the actor system the connector runs on, installed together as a suite's shared resource. Pekko's
 * own testkit owns the system, so a failing test cannot leave its threads running.
 */
class KafkaKit internal constructor(val broker: KafkaBroker, private val testKit: ActorTestKit) {

    /** What `run`, `start` and the connector's settings take. */
    val system: ClassicActorSystemProvider get() = testKit.system()
}

/** A [KafkaKit] whose actor system is named [name]. */
fun kafkaKit(name: String): Resource<KafkaKit> = resource {
    val broker = kafkaBroker().bind()
    val testKit = install({ ActorTestKit.create(name) }) { kit, _ -> kit.shutdownTestKit() }
    KafkaKit(broker, testKit)
}
