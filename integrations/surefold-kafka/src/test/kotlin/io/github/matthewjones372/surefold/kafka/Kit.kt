package io.github.matthewjones372.surefold.kafka

import dev.surefold.Resource
import dev.surefold.resource
import io.github.matthewjones372.surefold.stream.Forks
import io.github.matthewjones372.surefold.stream.PekkoStreams
import io.github.matthewjones372.surefold.stream.StreamBackend
import org.apache.pekko.actor.testkit.typed.javadsl.ActorTestKit

/** The two backends a consumer loop is run on, by the name a test per backend carries. */
val backends: List<String> = listOf("Forks", "Pekko")

/**
 * A broker, and the actor system Pekko's backend runs on, installed together as a suite's shared resource. Pekko's
 * own testkit owns the system, so a failing test cannot leave its threads running.
 */
class KafkaKit internal constructor(val broker: KafkaBroker, private val testKit: ActorTestKit) {

    /** The backend named [on]: [Forks], or [PekkoStreams] on this kit's actor system. */
    fun backend(on: String): StreamBackend = when (on) {
        "Forks" -> Forks()
        else -> PekkoStreams(testKit.system())
    }
}

/** A [KafkaKit] whose actor system is named [name]. */
fun kafkaKit(name: String): Resource<KafkaKit> = resource {
    val broker = kafkaBroker().bind()
    val testKit = install({ ActorTestKit.create(name) }) { kit, _ -> kit.shutdownTestKit() }
    KafkaKit(broker, testKit)
}
